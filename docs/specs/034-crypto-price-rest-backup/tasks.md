# Tasks: 코인 시세 신선도 보강과 체결 가용성 확대

> 구현 순서대로 나열. 각 항목이 커밋 1개 단위다.
> **제약(모든 항목 공통)**: `CryptoCandleAndPriceIndependenceTest`와 MKT-003 과거틱 가드 테스트를 깨지 않는다 — 단정을 바꾸지 않고, 필요하면 stub만 보강한다.
> **항목 1을 B(항목 2)보다 먼저 할 필요는 없다.** B는 A와 독립적으로 성립하며 사용자 가치 대부분을 담당하므로, 급하면 항목 2를 먼저 떼어 별도 PR로 낼 수 있다. 아래 순서는 한 PR로 갈 때의 순서다.

- [x] **1. 체결 경로의 STALE 허용 (B)**
  `PriceQueryService.getOrderExecutionPrice`의 코인 분기가 `getCryptoDisplayPriceQuote`를 쓰도록 바꾸고 `getCryptoExecutionPriceQuote`를 제거한다(판정 규칙을 두 벌로 유지하지 않는다). `requireAvailable`은 `UNAVAILABLE`에만 예외를 던지므로 새 분기는 필요 없다 — 표시 판정이 주는 `STALE` quote가 그대로 통과한다. `PriceStore.isPriceAvailable`은 다른 소비자가 여전히 쓰므로 **남긴다**(주석에 코인 체결 경로가 더 이상 호출하지 않는다는 사실만 반영).
  `PriceQueryServiceTest`의 `getOrderExecutionPriceStillThrowsPriceUnavailableWhenCryptoTickIsStale`을 **새 동작을 고정하는 테스트로 바꾼다** — 이름과 단정을 새 동작에 맞게 반전시킨다. 연결 끊김·수신 이력 없음에서 여전히 거부되는 케이스를 함께 추가해 fail-closed 잔여선을 고정한다. `OrderExecutionServiceTest`에 stale 코인 시장가 주문이 체결까지 도달하는 케이스를 추가한다.

- [x] **2. `PriceStore`에 관측 시각(`observedAt`) 분리 도입 (A)**
  Redis 해시 `price:crypto:{symbol}`에 `observedAt` 필드를 추가한다(새 키는 만들지 않는다). `saveTick`은 기존 과거틱 가드(`receivedAt` 비교)를 **그대로 두고**, 가드를 통과했을 때 `observedAt = now(clock)`을 함께 기록한다. 새 메서드 `recordObservation(symbol, price, observedAt)`을 추가한다 — `observedAt`은 항상 갱신하고, `price`는 저장값과 다를 때만 갱신하며 `receivedAt`은 건드리지 않는다. `isStale` 판정 기준을 `observedAt`으로 바꾸되 임계값 10초는 유지한다. `observedAt`이 없는 기존 해시는 `receivedAt`으로 폴백한다. `CryptoPriceDto`에 `observedAt`을 싣는다.
  `PriceStoreTest`에 plan.md "테스트 계획 — `PriceStoreTest`"의 케이스를 전부 추가한다. 특히 **`recordObservation` 직후 더 이른 `receivedAt`의 `saveTick`이 정상 반영되는 케이스**를 반드시 포함한다(PRICE-REST-003, 가장 깨지기 쉬운 지점). MKT-003 기존 케이스가 회귀 없이 통과하는지 확인한다.

- [x] **3. `recordObservation`의 이벤트 발행 조건 확정 (A)**
  `recordObservation`은 **가격이 실제로 바뀌었을 때만** `CryptoPriceUpdatedEvent`를 발행한다(관측 시각만 갱신한 경우 미발행). `saveTick`의 발행 조건은 건드리지 않는다. `PriceStoreTest`에 발행/미발행 두 케이스를 추가한다. `LimitOrderTriggerListener`·`CryptoPriceStreamService`는 코드 변경 없이 소비자로만 남는다 — 두 리스너의 기존 테스트가 회귀 없이 통과하는지 확인한다.

- [x] **4. `BithumbRestTickerPoller` 운영 확장 + 주입 대상 전환 (A)**
  프로필을 `@Profile("!prod & crypto-real")`에서 `@Profile("prod | crypto-real")`로 바꾼다. 의존성을 `FakeBithumbFeedClient`(운영에 빈이 없다)에서 `PriceStore`로 교체하고 `emitTicks`가 `recordObservation`을 호출하도록 바꾼다. 폴링 주기(3초)·실패 시 회차 스킵·타임아웃 설정·`bithumb.feed.ticker.enabled` 격리 프로퍼티는 전부 그대로 둔다.
  `BithumbRestTickerPollerTest`를 `PriceStore` 목 검증으로 전환하고(기존 `MockRestServiceServer` 구성은 유지), 실패·타임아웃·비정상 상태코드·항목 누락에서 예외를 던지지 않는 기존 단정을 유지한다. `BithumbRestTickerPollerConditionalTest`로 프로필 변경 후에도 테스트 격리(`enabled=false` 시 빈 미생성)가 유지되는지 확인한다 — PRD C-005 위반 방지.

- [ ] **5. 스케줄러 풀 크기 상향 + 개수 주석 정합 (A)**
  `application.yml`의 `spring.task.scheduling.pool.size`를 16 → 17로 올린다(운영 프로필에 `@Scheduled` 1개 추가). `application.yml`과 `application-crypto-real.yml` 두 곳의 "스케줄 개수" 주석을 실제 값으로 함께 맞춘다 — 현재 16 vs 13으로 어긋나 있다(PRICE-REST-006). `NewsCollectionIntegrationTest.schedulingPoolIsLargeEnoughForEveryScheduledTask`가 통과하는지 확인한다.

- [ ] **6. 판정 경로 회귀 테스트 + 문서 동기화 + 최종 빌드**
  `PriceQueryServiceTest`에 A 적용 후의 케이스를 추가한다(연결 정상 + 관측 신선 → 표시 `AVAILABLE` / 관측도 오래됨 → 표시 `STALE`이지만 체결은 통과). 항목 1에서 고정한 fail-closed 잔여선 케이스가 여전히 통과하는지 확인한다.
  문서를 동기화한다 — `docs/api-contracts.md`의 `### 종목 현재가 조회` 절과 주문 절에서 `PRICE_UNAVAILABLE` 발생 조건 설명 갱신, `docs/prd.md` §3 "구현 현황"에 행 추가(근거: PR 번호 — PR 생성 후 채운다). MKT-004(spec 003·PRD)와 spec 032의 체결 차단 요구사항은 **이미 갱신 완료**다.
  `./gradlew build`를 실행해 전체 테스트·포맷·커버리지가 통과하는 것을 확인한다.

## 머지 전 수동 확인 (자동 테스트로 덮이지 않는 것)

`dev` 머지 = 즉시 배포이므로(ADR-0021) 아래는 PR 올리기 전에 사람이 직접 확인한다.

- [ ] `SPRING_PROFILES_ACTIVE=local,crypto-real ./gradlew bootRun`으로 실제 빗썸 REST를 받아, 체결이 뜸한 코인이 10초를 넘겨도 `status: "AVAILABLE"`을 유지하는지 확인 — 자동 테스트는 목이라 실제 응답 형식까지 검증하지 못한다. (A)
- [ ] 폴러를 일부러 끈 상태(`bithumb.feed.ticker.enabled=false`)로 띄워 화면에 "지연"이 뜨는 종목의 **시장가 주문이 실제로 체결되는지** 확인한다. (B)
- [ ] 같은 실행에서 BTC 등 활발한 종목의 가격이 **3초 주기가 아니라 웹소켓 체결 도착 즉시** 갱신되는지 확인(PRICE-REST-003이 실제로 지켜지는지). (A)
- [ ] 빗썸 공개 API 레이트리밋에 3초 주기 · 전 심볼 묶음 1회 호출이 저촉되지 않는지 확인. 저촉되면 주기를 늘리되 10초보다는 반드시 짧게 유지한다.
