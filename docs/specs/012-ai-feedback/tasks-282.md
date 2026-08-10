# Tasks: 012 AI 피드백 — 이슈 #282 (코인 매도 회고 REST 호출을 읽기 트랜잭션 밖으로 분리)

> **이 문서는 이슈 #282의 범위만 담는다.** `./tasks.md`는 이슈 #225 전용, `./tasks-275.md`는 이슈 #275 전용, `./tasks-244.md`는 이슈 #244 전용이고 이 이슈도 `plan.md` §진행 상태 8개 분할 표 **밖의 별도 이슈**다(선례 #198·#244·#275). 값·규칙·빈 경계의 정본은 `./spec.md` **§FEED-012 결정 5**이고, 계약은 바뀌지 않으므로 `docs/api-contracts.md`는 대상이 아니다. **여기에 값·구조를 다시 적지 않는다.**
>
> **작성 규칙(`docs/specs/README.md`)** — 항목 하나 = implementer 1회 투입 = 커밋 1개. `run-log.md`에 시각·에이전트·실행 명령·근거를 1줄씩 남긴다(장문 금지). 테스트 레벨은 ADR-0003을 따른다.
>
> **정본은 이슈 #282 본문 체크리스트(3건)**이다. 이 문서 §완료 조건 소유가 그 항목과의 대응이다.

## 이미 끝난 것 — 다시 계획하지 않는다

없음. 이 이슈는 이번 계획 세션에서 §FEED-012 결정 5를 `spec.md`에 막 기록했고, 구현은 아직 시작되지 않았다.

## 이 이슈 전체에 걸리는 제약

- **주식 경로를 바꾸지 않는다** (이슈 §제외 범위). `StockPostSellFeedbackReader`는 현재 `PostSellFeedbackReader`의 주식 조립 코드를 값·순서 하나 바꾸지 않고 그대로 옮긴 것이어야 한다. 기존 주식 통합 테스트(`PostSellFeedbackBoundaryIntegrationTest`·`PostSellFeedbackGateIntegrationTest` 등)를 수정 없이 그대로 돌려 통과를 확인한다.
- **새 마이그레이션이 없다.** 이 이슈는 Java 코드의 빈 경계만 재배치하고 스키마·엔티티를 건드리지 않는다.
- **새 엔드포인트·응답 계약 변경이 없다.** `PostSellFeedbackResponse`의 필드 집합과 값은 그대로다 — 트랜잭션 경계는 관측 가능한 API 동작이 아니다.
- **`TradeService.getOwnedTrade`의 fetch 전략을 바꾸지 않는다.** `order` 도메인이 소유한 공유 메서드라 다른 호출부(투자일기 등)에 영향을 준다. lazy 초기화는 `feedback` 패키지 안의 `PostSellFeedbackContextReader`가 국소적으로 담당한다(§FEED-012 결정 5).
- **캐시를 도입하지 않는다.** ADR-0015가 매도 직후 피드백 조회를 캐시 제외 범위로 이미 못박았다.
- **200봉 상한을 조정하지 않는다.** §FEED-012 결정 4가 이미 범위 제외로 판정했고 이 이슈와 무관하다.

## 작업 항목

- [x] **1. `PostSellFeedbackContextReader`·`StockPostSellFeedbackReader` 신설, `PostSellFeedbackReader`를 오케스트레이터로 전환**

  §FEED-012 결정 5의 빈 경계 표를 그대로 구현한다.

  - `PostSellFeedbackContextReader`(신설, package-private `@Component`): `@Transactional(readOnly = true)` 메서드 하나(예: `loadContext(Long userId, Long tradeId)` → 새 package-private record `PostSellFeedbackContext(Trade trade, SellAllocationSummaryDto allocation)`). 내부 순서는 기존과 동일하게 유지한다 — `tradeService.getOwnedTrade` → 매도 체결 검증(400, **allocation 조회보다 먼저**) → `sellAllocationQueryService.getSellAllocationSummary`. **반환 직전에** `org.hibernate.Hibernate.initialize(trade.getInstrument())`와 `Hibernate.initialize(trade.getStockReplaySession())`를 호출한다(결정 5의 lazy 초기화 문단 — 빠뜨리면 컴파일·단위 테스트는 통과하고 실제 DB에서만 500이 난다).
  - `StockPostSellFeedbackReader`(신설): 현재 `PostSellFeedbackReader.read()`의 주식 조립 코드 전부(§파생 사실 계산 — `findHoldExtremes`·`isAfterMarketClose`·`buildPostSellFlow`·`buildCounterfactuals`·`scenarioAtClose`·`scenarioAtHoldHigh`·`scenarioAtFirstMoveAfterBuy`·`lastCandle`·`highestCloseAfter`·`buildPeerComparison`·`findHeldPriceMoves`·`revealCutoff`·`toHeldPriceMoveItem`·`buyToNewsMinutes`·`isSameSessionCompleted`·`sourceTradingDateOf`·`serviceDateOf`·`atOriginTradeDate`·`returnRate`·`holdingMinutes`·`isReversed`와 관련 상수 `PAST_SERVICE_DATE_CUTOFF`·`STOCK_FEE_RATE`)를 **값 하나 바꾸지 않고** 옮긴다. 시그니처는 `@Transactional(readOnly = true) PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation)` — `CryptoPostSellFeedbackReader.read(Trade, SellAllocationSummaryDto)`와 대칭이 된다.
  - `PostSellFeedbackReader`를 오케스트레이터로 축소한다 — `@Transactional` 제거, `contextReader.loadContext(userId, tradeId)` → `market == Market.CRYPTO ? cryptoPostSellFeedbackReader.read(trade, allocation) : stockPostSellFeedbackReader.read(trade, allocation)`. 옮겨진 필드·메서드 참조를 정리해 컴파일이 통과하게 한다. **클래스 Javadoc은 이 항목에서 전면 개정하지 않는다** — "알려진 한계" 문단 제거를 포함한 전체 정리는 4번 항목이 담당한다.
  - 기존 `PostSellFeedbackReaderTest`를 분해한다 — buyAt 선정·holdingMinutes·sameSessionCompleted·returnRate 등 주식 조립 검증(원장 수치·파생 사실 관련 테스트 다수)은 새 `StockPostSellFeedbackReaderTest`로 옮기고, 원래 파일은 **오케스트레이션만** 남긴다(400/404/403 위임 순서, 시장 분기, 코인 위임). `delegatesCryptoSellTradeToTheCryptoReaderInsteadOfRejectingIt` 같은 위임 테스트는 새 생성자·mock 대상(`postSellFeedbackContextReader`·`stockPostSellFeedbackReader`·`cryptoPostSellFeedbackReader`)에 맞춰 갱신한다.
  - 검증 — 단위(`PostSellFeedbackReaderTest`·`StockPostSellFeedbackReaderTest`·`PostSellFeedbackContextReaderTest`). **이 항목만으로는 lazy 초기화 회귀를 잡지 못한다** — mock은 실제 Hibernate 세션을 거치지 않는다. 3번 항목의 통합 테스트가 그 역할을 한다.

- [x] **2. `CryptoPostSellFeedbackDbReader` 신설, `CryptoPostSellFeedbackReader`를 결정 5의 4단계 순서로 재구성**

  - `CryptoPostSellFeedbackDbReader`(신설): `@Transactional(readOnly = true)` 메서드 둘 — `findHeldPriceMoves(Trade trade, LocalDateTime buyAt, LocalDateTime sellAt)`(트랜잭션 B)과 `buildPeerComparison(List<HeldPriceMoveItem> priceMoves)`(트랜잭션 C). 현재 `CryptoPostSellFeedbackReader`의 같은 이름 메서드(+`toHeldPriceMoveItem`)를 그대로 옮긴다. `priceMoveEventRepository`·`priceMoveSourceLoader`·`priceMovePeerStatRepository`·`cryptoProperties`(`rollingWindowMinutes` 용) 의존성도 함께 옮긴다.
  - `CryptoPostSellFeedbackReader.read()`(여전히 `@Transactional` 없음)의 호출 순서를 결정 5 그대로 재배열한다 — `dbReader.findHeldPriceMoves(trade, buyAt, sellAt)` → `findHoldExtremes`·`isAfterDayClose`·`sellDayClose`·`buildPostSellFlow`·`buildCounterfactuals`(전부 REST/순수, 기존 순서·내용 유지) → `dbReader.buildPeerComparison(priceMoves)` → `PostSellFeedbackResponse` 조립. 이 클래스에서 `priceMoveEventRepository`·`priceMoveSourceLoader`·`priceMovePeerStatRepository` 의존성을 제거하고 `cryptoPostSellFeedbackDbReader` 하나로 교체한다.
  - 기존 `CryptoPostSellFeedbackReaderTest`를 갱신한다 — `findHeldPriceMoves`/`buildPeerComparison`에 걸린 픽스처(`givenHeldCard` 등)는 `cryptoPostSellFeedbackDbReader`를 mock으로 주입해 남기거나 새 `CryptoPostSellFeedbackDbReaderTest`로 옮긴다(구현자가 더 자연스러운 쪽을 고르되, 두 관심사 — REST/극값 계산과 DB 조회 — 가 한 파일에 섞여 남지 않게 한다).
  - **REST 호출 대상·순서·흡수 규칙(`MARKET_DATA_PROVIDER_ERROR` 폴백)은 바뀌지 않는다** — 이번 항목은 트랜잭션 경계 재배치일 뿐 계산·폴백 로직을 수정하지 않는다.
  - 검증 — 단위(`CryptoPostSellFeedbackReaderTest`·`CryptoPostSellFeedbackDbReaderTest`).

- [x] **3. 트랜잭션 경계 통합 테스트 — REST 호출 시점에 활성 트랜잭션이 없음을 실제 DB로 고정**

  spec §FEED-012 결정 5가 요구하는 두 층의 검증 중 실제 DB를 거치는 층이다. ADR-0003의 "핵심 시나리오는 Testcontainers 통합 테스트" 근거.

  - 신설 통합 테스트(예: `CryptoPostSellFeedbackTransactionBoundaryIntegrationTest`, Testcontainers MySQL). **엔티티를 `Trade.of(...)`로 직접 만들지 않는다** — 실제로 저장(주문·체결·배분·lot)한 뒤 리포지터리로 다시 조회한 `Trade`를 써야 `instrument`·`stockReplaySession`이 진짜 lazy 프록시로 남아 lazy 초기화 누락을 재현할 수 있다.
  - `CryptoCandleProvider`(또는 그 아래 REST 클라이언트)를 감시용 테스트 더블로 바꿔, 캔들 조회가 호출되는 순간 `TransactionSynchronizationManager.isActualTransactionActive()`를 캡처한다. **캔들 조회 4곳 모두에서 `false`임을 단언한다** — 사용자가 사전에 합의한 검증 방식이다.
  - `priceMoves`(트랜잭션 B)·`peerComparison`(트랜잭션 C) 조회 시점에는 반대로 `true`임을 단정해, "트랜잭션이 통째로 사라진 것"과 "정확히 REST 구간에서만 닫힌 것"을 구별한다.
  - `PostSellFeedbackReader.read(...)`가 예외 없이 200에 해당하는 `PostSellFeedbackResponse`를 반환하는지도 단정한다 — `Hibernate.initialize`를 빠뜨리면 이 테스트가 `LazyInitializationException`으로 실패하므로, 이 단정 하나가 곧 lazy 초기화 회귀 검증이다.
  - **주식 회귀** — 기존 `PostSellFeedback*IntegrationTest` 전부를 **수정 없이** 그대로 실행해 통과를 확인한다(회귀가 나면 원인은 1번 항목의 이동이므로 테스트가 아니라 구현을 고친다).
  - 검증 — Testcontainers(MySQL) 통합.

- [x] **4. 문서 정리 — `PostSellFeedbackReader` Javadoc·spec·prd 정합 확인**

  이슈 §체크리스트 3번째 항목("알려진 한계" 문단 제거)을 여기서 닫는다.

  - `PostSellFeedbackReader`의 클래스 Javadoc을 새 구조에 맞게 다시 쓴다 — "알려진 한계 — 코인 분기는 이 읽기 트랜잭션 안에서 외부 REST를 부른다"(이슈 #282) 문단을 **걷어낸다**. "LLM 호출을 이 트랜잭션 안에 넣지 않기 위해 빈을 나눴다"는 문장도 이 클래스 자체가 이제 트랜잭션을 갖지 않는(오케스트레이터인) 사실에 맞춰 고친다 — 예: 코드가 `PostSellFeedbackService`와 같은 무트랜잭션 오케스트레이터 형태를 이제 두 겹(서비스 → 리더)으로 반복한다는 점을 남긴다.
  - `spec.md` §FEED-012 결정 5가 실제 구현(클래스명·메서드명·트랜잭션 경계)과 정확히 일치하는지 최종 대조한다 — 계획 단계에서 이미 기록해 뒀으므로 이 항목은 **구현 결과와의 정합만** 본다.
  - `docs/prd.md` §3은 **대상이 아니다.** FEED-007·010·011은 이미 완료로 기록돼 있고 코인 지원도 #275가 이미 반영했다. 이 이슈는 응답 계약·제공 기능을 늘리거나 줄이지 않고 내부 트랜잭션 경계만 재배치하므로 CLAUDE.md 규칙 10의 "제공하는 기능이 그대로인 변경"에 해당한다(`tasks-244.md` 5번 항목과 같은 판단).
  - `docs/api-routes.md`·`docs/api-contracts.md`도 **대상이 아니다** — 새 엔드포인트나 응답 계약 변경이 없다.
  - 검증 — 없음(문서 대조뿐, 코드 검증 아님).

## 완료 조건 소유

정본은 **이슈 #282 본문 체크리스트(3건)**다. `spec.md` §완료 조건에는 이 이슈 전용 절이 없다(§FEED-012 결정 5가 규칙·구조를 적고 완료 판정은 이슈가 쥔다). `plan.md` §완료 조건 배정의 "총 91건" 표에는 들어가지 않는다 — 그 표는 8개 분할(#147~#225) 전용이다.

| # | 이슈 #282 체크리스트 | 항목 |
|---|---|---|
| 1 | 코인 경로가 필요한 캔들을 트랜잭션 밖에서 먼저 읽고, 원장 조회 트랜잭션과 분리해 조립하도록 순서를 바꾼다 | **1**(트랜잭션 A + 오케스트레이터화) + **2**(트랜잭션 B·C·REST 순서) |
| 2 | 읽기 트랜잭션 안에서 외부 호출이 일어나지 않는지 테스트로 고정한다 | **3** |
| 3 | `PostSellFeedbackReader` 클래스 주석의 "알려진 한계" 문단을 걷어낸다 | **4** |

## 이 이슈에서 하지 않는 것

- **주식 경로 변경** — `StockPostSellFeedbackReader`는 코드 위치만 옮기고 값·순서를 바꾸지 않는다(이슈 §제외 범위, `tasks-275.md`와 같은 원칙).
- **`TradeService.getOwnedTrade`의 fetch 전략 변경** — `instrument`·`stockReplaySession`을 이 메서드 자체에서 eager로 바꾸지 않는다. lazy 초기화는 `feedback` 패키지 안의 `PostSellFeedbackContextReader`가 국소적으로 담당한다.
- **캐시 도입** — ADR-0015 §후속이 매도 직후 피드백 조회를 캐시 제외 범위로 이미 못박았다("매도 회고는 개인 데이터라 공유 대상이 아니다").
- **200봉 상한 조정** — §FEED-012 결정 4가 이미 범위 제외로 판정했고 이 이슈와 무관하다.
- **새 엔드포인트·응답 계약 변경** — 없음. `docs/api-contracts.md`는 대상이 아니다.
- **새 ADR** — §FEED-012 결정 5가 이미 "필요하지 않다"고 판정했다(`PostSellFeedbackService`의 기존 패턴을 코인 REST 호출에 반복 적용할 뿐, 결정할 새 구조가 없다).
- **REST 호출의 계산·폴백 로직 변경** — `MARKET_DATA_PROVIDER_ERROR` 흡수, 극값·반사실 계산식은 이번 이슈에서 손대지 않는다. 트랜잭션 경계 재배치만 한다.
