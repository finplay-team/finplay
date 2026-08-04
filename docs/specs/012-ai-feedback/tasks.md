# Tasks: 012 AI 피드백 — 이슈 #208 (매도 직후 피드백 조회 API)

> **이 문서는 이슈 #208의 범위만 담는다.** spec 012 전체(이슈 8개)의 분할은 `./plan.md`가 정본이고, 값·규칙은 `./spec.md` §확정값이 정본이다. 여기에 값을 다시 적지 않는다 — 벽시계와 분봉의 구분은 §C-2-1, 상태값은 §C-4, 노출 게이트와 재생성 게이트는 §C-5, 패키지 배치와 클래스 이름은 §C-6, 설정 방침과 `max-narrative-retry`는 §C-7, 컬럼 타입은 §C-8, 파생 사실 계산식은 §파생 사실 계산, 반사실 가격의 정의는 §반사실·집단 비교 계산, 후검증 목록은 §후검증, 템플릿 문장은 §템플릿 문장, 실패 시 대응은 §실패 처리, 응답 필드·수치 산출·예시 값은 `docs/api-contracts.md`의 "매도 직후 피드백 조회" 소절이다.
>
> 항목 하나 = implementer 1회 투입 = 커밋 1개 (`docs/specs/README.md`의 굵기 가이드). 테스트 레벨은 ADR-0003을 따른다 — 수치·파생 사실 계산은 단위, 리포지토리 파인더와 유니크 축은 `@DataJpaTest`, 컨트롤러 계약은 `@WebMvcTest`, 노출 게이트와 재생성 종단은 고정 `Clock` + Testcontainers 통합이다.
>
> **이 이슈는 spec 012의 네 번째(마지막) 컨트롤러를 만든다.** `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 컨트롤러가 생기는 1번 커밋에서 함께 하고(CLAUDE.md 규칙 7), 마지막에 /feature 마무리의 planner(동기화 모드)가 실제 매핑과 두 문서를 대조한다. 6번 항목이 그 자리다.

## 이 이슈 전체에 걸리는 제약

- **여기서 값을 새로 정하지 않는다.** 임계값·시각·범위·상태값·클래스명이 필요하면 위 참조를 읽는다. spec에 없는 값·이름이 필요해 보이면 코드가 아니라 `spec.md`를 먼저 고치고 오케스트레이터에게 보고한다 (CLAUDE.md 규칙 1·2). #167이 `NewsCollectionService`를, #188이 `MarketSessionTimes`·`BriefingNewsItem`을 §C-6에 추가한 것이 선례다.
- **6·7번 경계를 아래 표대로 고정한다** (2026-08-04 확정, 이슈 #208 본문 §6·7번 경계). 두 이슈가 같은 `PostSellFeedbackResponse`에 필드를 더하므로 동시에 진행하지 않고, **7번이 끼울 자리를 코드 주석으로 남긴다** (3번 항목이 그 자리다).

  | 필드 | 6번 (이 이슈) | 7번 |
  |---|---|---|
  | `postSellFlow` | 게이트 판정 + 값 전체 | — |
  | `counterfactuals.status` | 게이트 판정 (§C-5) | — |
  | `counterfactuals` 3종의 `price`·`at` | **채운다** | — |
  | `counterfactuals` 3종의 `returnRate` | `null` | 수수료 재계산해 채운다 |
  | `peerComparison.status` | 항상 `NOT_YET` | 확정 집계 행 기준 판정 (`NO_EVENT` 1순위) |
  | `peerComparison` 지표 | `null` | 전부 |

  `sameSessionCompleted=false`면 `counterfactuals`·`peerComparison`은 **필드 자체가 `null`**이고 `priceMoves`는 `[]`다 — `docs/api-contracts.md`가 이미 정했으므로 **여기서 다시 정의하지 않는다.**
- **이미 있는 것을 다시 만들지 않는다.** 아래는 앞 이슈에서 머지된 것이고, 같은 뜻의 두 번째 경로를 만들면 규칙이 갈리는데 예외도 로그도 남지 않는다.

  | 이미 있는 것 | 이 이슈가 할 일 |
  |---|---|
  | `TradeService.getOwnedTrade` — 체결 단건 + 소유권 검증 (404 `NOT_FOUND` → 403 `FORBIDDEN` 순서까지 구현돼 있다) | 주입해 쓴다. `order`에 조회를 새로 더하지 않는다 |
  | `trade_feedbacks` 테이블 (V13) + `TradeFeedback` 엔티티 + `TradeFeedbackRepository` | **머지된 마이그레이션을 고치지 않는다** (ADR-0004). 컬럼을 더할 근거를 발견하면 §C-8·§데이터 모델을 확인한 뒤 **dev 기준 다음 번호(현재 V22)**로 제안한다. 리포지토리에 조회 메서드가 아직 없고, 엔티티에도 재생성·확정 상태를 바꾸는 메서드가 없다 — **이 이슈가 자기 완료 조건과 함께 더하는 것이 원래 설계다**(두 파일 주석에 그렇게 적혀 있다) |
  | `NarrativeService.resolvePostSellNarrative` + `PostSellPromptDto`·`HeldPriceMoveDto`·`NewsSourceDto` + `NarrativeTemplateBuilder.postSellTemplate` | `NarrativeService` **하나만 주입한다**(§C-6). 매도 회고는 **1단계 경로**다 — 생성 → 후검증 → 걸리면 템플릿, **재생성 없음.** 생성기·검증기·프롬프트 조립기·템플릿 조립기를 직접 알 필요가 없다 |
  | `MarketSessionTimes` — 개장·장 마감의 벽시계 경계 (§C-6, #188 신설) | §C-5의 게이트가 쓰는 15:30이 여기 있다. **상수를 새로 선언하지 않는다.** 값의 성격은 §C-2-1이 벽시계로 못박았다 — **분봉을 찾는 값이 아니다** |
  | `NewsItem`(최상위 record)·`NewsItemTruncator`(목록 정렬·절단) | `priceMoves[].sources`가 그대로 `NewsItem`이다. **중첩으로 복제하지 않는다.** 목록을 자를 일이 있으면 규칙을 복사하지 않고 `NewsItemTruncator`를 쓴다 |
  | `StockReplayService.getFullDayCandles`·`getPreviousTradingDayClose` (#180이 열었다) | **재생 노출 게이트를 우회한다.** §C-5 게이트를 통과한 뒤에만 부르고 **판정은 호출부 책임**이다 — 메서드 주석에 그 조건이 적혀 있다 |
  | `portfolio`의 `TradeAllocationRepository`·`HoldingLotRepository` | **필요한 조회만 더한다.** `feedback`은 다른 도메인의 repository·store를 직접 주입하지 않고 **서비스를 경유한다**(§C-6·`docs/conventions.md`) |
  | `Trade.stockReplaySession` (V20) — 주식 체결이 발생한 재생세션. `serviceDate`와 `sourceTradingDate`를 갖고 코인 체결은 `NULL`이다 | `sameSessionCompleted` 판정과 §C-5 게이트의 "그 체결의 서비스 날짜"가 이 값으로 결정된다. 별도 변환 경로를 만들지 않는다 |
  | `feedback.llm.max-narrative-retry` — yml·record `@DefaultValue`·드리프트 테스트 2종이 전부 있다 | 읽어 쓰기만 한다. **설정 항목을 새로 세울 필요가 없다** |

- **LLM 호출을 트랜잭션 안에 넣지 않는다.** #180이 정확히 이 자리에서 한 번 되돌렸고 `PriceMoveCardService` → `PriceMoveCardWriter`가 그 결과다 — 읽기·LLM 호출은 트랜잭션 밖, **저장만 별도 `@Transactional` 컴포넌트**에 둔다. **자기호출은 프록시를 타지 않으므로 같은 클래스의 private 메서드에 애노테이션을 붙이면 무효이고, 정확히 막으려던 상태가 조용히 된다.** 이것은 독립 항목이 아니라 **서술을 저장하는 항목(4·5번)의 제약**이다 — 경계만 옮기는 커밋을 따로 내면 그 사이 커밋이 경계 없이 도는 상태로 남는다.
- **조회 경로에 LLM이 들어오는 첫 이슈다.** 지금까지 조회 3종은 배치 산출물을 읽기만 해서 호출이 0건이었다. **spec이 정한 동작이므로 바꾸지 않되**(FEED-007 — `docs/conventions.md`의 "GET은 부수효과 없음"에 대한 이 spec의 유일한 예외, 체결 1건당 1회) 타임아웃 처리 경로를 확인한다.
- **`LlmCallStats`는 배치 스코프 전용이고 조회 호출을 세지 않는다**(#198, 의도된 동작). 조회 쪽 계측이 필요하다고 판단되면 **이 이슈에서 하지 않고 별도 이슈를 제안한다.**
- **소요 시간을 잴 일이 생기면 `System.nanoTime()`을 쓴다.** 이 저장소는 시각을 `Clock`으로 주입받고 테스트가 고정 `Clock`으로 바꾸므로, `Clock`으로 재면 **통합 테스트에서 항상 0이 나오면서 통과한다**(#198이 회귀 테스트까지 남겼다).
- **정렬에 2차 키를 두고 이름과 계약 문장에 남긴다.** `priceMoves`는 `windowStart` 오름차순 + `id` 오름차순, 각 카드의 `sources`는 `publishedAt` 내림차순이다(`docs/api-contracts.md`). **2차 키를 지우는 회귀는 테스트가 반드시 잡지 못한다** — InnoDB가 흔히 PK 순서로 돌려주어 우연히 일치한다. 그래서 보호를 **파인더 이름·계약 문장 양쪽에** 남긴다 (#180이 카드 정렬에서 같은 지적을 받았다).
- **픽스처가 틀린 구현을 실제로 잡는지 먼저 확인한다.** 이 spec은 **예외도 로그도 없이 조용히 0건이 되는 실패**가 많아 통과 여부만 보는 단정은 의미가 없을 때가 있다 — 특히 `sameSessionCompleted`, 카드 0건 재생성, 게이트 3건이 그렇다. #180에서 "결측 구간 점수" 조건이 **픽스처를 잘못 잡으면 맞는 구현과 틀린 구현이 같은 답을 내는 것**을 발견했다. **각 항목의 검증 문구에 "이 픽스처로 규칙을 지운 구현이 실제로 빨간지 확인한다"를 함께 적었고, 확인 없이 초록만 보고 넘기지 않는다.**
- **컬럼을 더하면 `FeedbackSchemaConstraintsTest`의 기대 맵이 함께 바뀐다** — 일곱 테이블의 컬럼 → NULL 허용 맵을 통째로 비교하는 **의도된 설계**다. 기대 맵을 지우는 방향으로 가지 않는다.
- **설정 테스트를 새로 만들면 `docs/agent-mistakes.md` 2026-08-04 행의 `spring.config.additional-location` / `ConfigDataApplicationContextInitializer` 함정을 먼저 읽는다.** 그 프로퍼티는 `@SpringBootTest`와 **똑같이** 해석되므로 초기화자를 쓰는 드리프트 테스트가 크론 값 단정에서 깨진다. **이 이슈는 새 설정 키를 세우지 않으므로 애초에 그 자리가 없어야 한다.**
- **`RestClient` 빈을 새로 정의하지 않는다.** 필드 `@Qualifier`가 Lombok 생성자에 복사되지 않아 실효가 없고, 두 번째 빈이 생기는 순간 `NoUniqueBeanDefinitionException`으로 앱 전체가 죽는다 (`docs/agent-mistakes.md` 2026-08-04). **이 이슈는 새 외부 HTTP 호출을 만들지 않으므로 애초에 해당 자리가 없어야 한다.**
- **외부 API 키가 없어도 기동과 `./gradlew build`가 통과해야 한다** (ADR-0011, §실패 처리). **실제 외부 API를 호출하는 자동 테스트를 만들지 않는다** (PRD C-005).
- **원장에 쓰지 않는다.** 이 이슈가 쓰는 테이블은 `trade_feedbacks` 하나뿐이고 수치는 원장에서 **읽기만** 한다. 8개 이슈 공통 조건인 "원장 불변"이 이 이슈에서 취하는 형태이며, **회원별 쓰기가 처음 생기는 이슈라 특히 위험하다.**
- **배치를 건드리지 않는다.** 이 이슈는 조회 경로만 만든다 — `FeedbackBatchService`·`CryptoFeedbackBatchService`의 호출 순서·시그니처에 손대지 않는다.

## 작업 항목

- [x] **1. 체결 검증과 수치 요약 — 컨트롤러·조회 서비스 골격**

  본인 매도 체결 1건의 **원장 수치만** 돌려주는 최소 응답을 세운다. **spec 012의 네 번째 컨트롤러이고, 뒤 항목 전부가 이 서비스에 필드를 채워 넣는다.**
  - 경로·응답 필드·오류 형식·수치 산출식은 `docs/api-contracts.md`의 "매도 직후 피드백 조회" 소절과 FEED-007이 정본이다. 클래스 이름은 §C-6(`PostSellFeedbackController`·`PostSellFeedbackService`·`PostSellFeedbackResponse`)이다.
  - **검증 순서는 `getOwnedTrade`가 이미 정한 `존재(404) → 소유(403)`를 그대로 타고, 그 뒤에 `side != SELL` → 400, `market = CRYPTO` → 400이다.** 코인은 **빈 값을 채운 200을 돌려주지 않는다** — 2차 범위 밖이다(FEED-007 각주). 매도 회고 투자일기(`/sell-journal`, 007)가 같은 순서를 이미 쓰고 있으므로 형태를 본뜬다.
  - **수치는 원장에서 그대로 읽는다.** `buyPrice`는 `trade_allocations`의 FIFO 배분 가중평균 매수단가, `sellPrice`·`quantity`·`fee`·`realizedPnl`은 `trades` 행 그대로, `returnRate`는 계약이 정한 식과 scale·라운딩이다. **재계산하거나 LLM에게 계산시키지 않는다**(PRD C-004). `api-contracts.md`의 예시 값이 실제로 재현되므로 **픽스처를 그 예시로 만들어도 된다 — 값이 안 맞으면 예시가 아니라 구현이 틀린 것이다.**
  - **`buyAt`은 배분된 lot 중 가장 이른 `executed_at`이다**(FEED-007). 한 매도가 여러 lot에 배분되므로 "매수 시각"이 단일하지 않고, **이 값 하나가 `holdingMinutes`·`buyToNewsMinutes`·`minutesAfterBuy`·보유 구간 극값·반사실의 기준을 전부 결정한다.** 구현자가 고르게 두지 않는다.
  - **`sameSessionCompleted`는 배분된 lot 전부를 본다.** 각 lot의 매수 체결과 이 매도 체결의 `stockReplaySession.sourceTradingDate`를 대조하고, **하나라도 다르면 `false`다** — 가장 이른 lot 하나만 보고 판정하지 않는다.
  - `portfolio`에 **배분·lot 조회 메서드를 필요한 만큼만 더한다.** `feedback`은 repository를 직접 주입하지 않고 서비스를 경유한다(§C-6). 7번의 모집단 재구성도 같은 패키지에 조회를 더하므로 **없는 것만 더한다.**
  - **아직 채우지 않는 필드는 계약의 필드 집합을 유지한 채 `null`·`[]`로 둔다** — 2·3번이 각자 완료 조건과 함께 채운다. 필드를 나중에 더하면 그 사이 계약이 깨진 상태로 머지된다.
  - 컨트롤러가 생기므로 **같은 커밋에서 `api-contracts.md`의 매도 회고 소절 제목에서 `(계획)`을 걷고 `api-routes.md`의 2차 계획 라우트 절에서 그 행을 실제 라우트 표로 옮긴다** (CLAUDE.md 규칙 7). **표시가 남은 소절만 블랙박스 QA 근거에서 제외되므로** 남기면 이 API가 QA 대상에서 빠진다.
  - 검증 — `@WebMvcTest`(계약·401·404·403·400 넷) + 단위(수치 산출·`buyAt` 선정·`sameSessionCompleted` 판정) + `@DataJpaTest`(배분·lot 파인더). **완료 조건 5건이 이 항목 소유다.** 코인 400, 여러 lot의 `buyAt`(**2개 lot 픽스처**), 서로 다른 원본 거래일이면 `false`, 투자일기 없이 200, API 계약의 404·403·400이다. 401은 배정 건수에 넣지 않지만 이 엔드포인트에 적용한다. **`sameSessionCompleted` 픽스처는 "가장 이른 lot만 보는 구현"에서 실제로 빨간지 확인한다** — 두 lot의 원본 거래일이 같은 픽스처로는 두 구현이 같은 답을 낸다.

- [ ] **2. 파생 사실 — 보유 구간 극값·뉴스 대비 타이밍·보유 구간 카드**

  수치를 다시 읽어주는 것은 조회일 뿐이므로 **사용자가 직접 계산하지 않은 관계**를 서버가 계산해 응답에 넣는다(FEED-007).
  - 계산식은 §파생 사실 계산이 정본이다 — 보유 구간 극값과 `sellVsHighRate`·`sellVsLowRate`, `buyToNewsMinutes`, 카드별 `minutesAfterBuy`·`minutesBeforeSell`이다. **여기서 식을 다시 적지 않는다.**
  - **극값은 분봉 `close`만 쓴다. `high`/`low` 컬럼을 쓰지 않는다.** 이 서비스의 시장가 체결은 직전 완료 분봉의 종가로만 이루어지므로 `high`로 잡으면 **사용자가 애초에 얻을 수 없었던 가격**이 되고, 3번이 그 값을 반사실 표에 올리면 실현 불가능한 수익률로 후회를 유도하는 셈이 된다.
  - **`buyToNewsMinutes`의 부호를 뒤집지 않는다** — 매수가 기사보다 앞이면 **양수**다. 근거 기사가 없으면 `null`이고 프롬프트의 `firstNewsAt`도 함께 `null`이다(`PostSellPromptDto` 주석).
  - **`priceMoves`에도 카드 노출 게이트가 걸린다**(§C-5). 근거 기사가 `windowEnd` 이후에 발행될 수 있어 게이트를 빼면 **Part A·C보다 먼저 그 기사를 보게 된다.** #180의 `PriceMoveQueryService`가 같은 게이트를 이미 쓰고 있으므로 판정 방식을 본뜨되, **보유 구간(`buyAt` ~ `sellAt`)으로 좁히는 파인더는 이 항목이 더한다** — 정렬 두 키를 파인더 이름에 드러낸다.
  - 분봉은 `StockReplayService.getFullDayCandles`로 읽는다. **게이트를 우회하는 메서드이므로 §C-5 판정 뒤에 부르는 것이 호출부 책임이고**, 보유 구간 극값은 매도 시각까지만 보므로 이미 재생된 구간이다 — **매도 이후 구간을 여기서 건드리지 않는다**(3번 소유).
  - **`sameSessionCompleted=false`면 이 항목이 계산하는 전부를 `null`로 두고 `priceMoves`는 `[]`다** — 분봉이 불연속이라 계산이 성립하지 않는다. 계약이 이미 정한 형태다.
  - 검증 — 단위(극값·부호·간격) + 고정 `Clock` + Testcontainers 통합(카드 게이트). **완료 조건 3건 + 공유 1건이 이 항목 소유다.** 파생 사실 정확 계산, 극값이 `close` 기준, 게이트 ⑮(`priceMoves` 카드 게이트)이고 `sameSessionCompleted=false` 조건의 파생 사실·`priceMoves` 절반이다. **극값 픽스처는 `high`/`low`가 `close`와 다른 봉을 반드시 포함한다** — 세 값이 같은 픽스처는 `high`를 쓴 구현에도 초록이다.

- [ ] **3. 매도 후 흐름·반사실 가격과 시각 게이트 — 7번이 끼울 자리 남기기**

  장 마감 뒤에만 열리는 세 묶음을 채운다. **6·7번 경계표가 이 항목에서 실제 코드가 된다.**
  - 게이트는 §C-5다 — **"오늘 15:30"이 아니라 "그 매도 체결의 서비스 날짜 15:30"이다.** 오늘로 잡으면 **어제 판 체결을 오늘 오전에 열었을 때 `READY`였던 값이 `NOT_YET`으로 되돌아간다.** 서비스 날짜는 `Trade.stockReplaySession.serviceDate`이고 15:30은 `MarketSessionTimes`다.
  - `postSellFlow`는 **게이트 판정과 값 전체가 6번**이다 — `closePrice`·`closeAt`·`sellToCloseRate`·`postSellHighPrice`·`postSellHighAt`이며 계산식은 §파생 사실 계산이다. 게이트 전에는 `status="NOT_YET"`이고 **가격 필드가 전부 `null`**이다.
  - `counterfactuals`는 **`status`와 3종의 `price`·`at`까지가 6번, `returnRate`는 `null`로 두고 7번이 수수료를 재계산해 채운다.** 가격 정의는 §반사실·집단 비교 계산이다 — `atClose`·`atHoldHigh`·`atFirstMoveAfterBuy`이며 **보유 구간에 카드가 없으면 `atFirstMoveAfterBuy`가 `null`이고, 매도 이후의 카드는 쓰지 않는다**(보유하지 않은 구간이다).
  - `peerComparison`은 **`status`를 항상 `NOT_YET`으로 두고 지표 전부를 `null`로 둔다.** 확정 집계 행 기준 판정(`NO_EVENT` 1순위)과 지표 계산은 7번이다 — **여기서 `NO_EVENT`나 `INSUFFICIENT_SAMPLE`을 임의로 판정하지 않는다.**
  - **7번이 끼울 자리를 코드 주석으로 남긴다** (이슈 #208 본문이 요구한다). `returnRate`가 `null`인 이유, `peerComparison.status`가 상수 `NOT_YET`인 이유, 그 둘을 7번이 어느 규칙으로 채우는지를 응답 조립 지점에 적는다. **주석이 없으면 7번이 이 자리를 "구현 누락"으로 읽고 경계를 다시 정한다.**
  - **`atClose`·`closePrice`는 "그 거래일 마지막 분봉"의 close다** — `15:30`을 리터럴 시각으로 찾으면 **없는 날 `null`이 되고 예외는 안 난다**(§C-2-1). 수집기가 `09:00~15:30`을 허용하지만 15:30 분봉이 오는 것은 보장되지 않는다.
  - **`sameSessionCompleted=false`면 `counterfactuals`·`peerComparison`은 필드 자체가 `null`이다** — `status`만 담은 껍데기를 내리지 않는다. 계약이 이미 정한 형태다.
  - `api-contracts.md`의 매도 회고 소절에 **7번 머지 전까지 `returnRate`가 `null`이라는 것을 명시한다.** 7번이 그 문장을 걷어낸다.
  - 검증 — 고정 `Clock` + Testcontainers 통합(게이트 직전·직후 두 시각, 날짜를 하루 넘긴 조회) + `@WebMvcTest`(직렬화) + 단위(반사실 가격 선정). **완료 조건 4건 + 공유 1건이 이 항목 소유다.** `atClose`·`closePrice`가 마지막 분봉, 게이트 ⑬(게이트 전 `NOT_YET`·가격 필드 빔), 게이트 ⑭(전날 매도 건이 다음 날 장중에도 `READY`), 응답 직렬화가 계약 필드 집합과 일치(`priceMoveId`·`narrativeSource`·`buyAt`·`sellAt` 포함)이고 `sameSessionCompleted=false` 조건의 반사실·집단 비교 절반이다. **마지막 분봉 픽스처는 15:30 분봉이 없는 날로 만든다** — 15:30 봉이 있는 픽스처는 리터럴 구현에도 초록이다. **게이트 픽스처는 "오늘 15:30"으로 잡은 구현이 실제로 빨간지 확인한다** — 같은 날 조회만 재현하면 두 구현이 같은 답을 낸다.

- [ ] **4. AI 서술 — 최초 조회 생성·저장·재사용과 템플릿 폴백**

  1~3번이 모은 수치·파생 사실을 근거로 회고 문장을 만들어 `trade_feedbacks`에 저장하고 이후 재사용한다.
  - `NarrativeService.resolvePostSellNarrative` **하나만 주입한다**(§C-6). 매도 회고는 **1단계 경로**다 — 생성 → 후검증 → 걸리면 템플릿, **재생성 없음.** 프롬프트 입력 `PostSellPromptDto`와 템플릿 조립은 #147이 이미 만들어 뒀으므로 **값을 채워 넘기기만 한다.**
  - **반사실을 프롬프트에 넣지 않는다** — `PostSellPromptDto`에 그 필드가 애초에 없고, 그 이유가 주석에 있다(§왜 반사실은 AI 문장에 넣지 않는가). **필드를 추가하고 싶으면 spec을 먼저 고친다.** 반대로 집단 비교는 관측된 사실이라 서술에 넣어도 되며 그 자리는 이미 nullable로 열려 있다.
  - **LLM 호출을 트랜잭션 안에 넣지 않는다.** 읽기·프롬프트 조립·LLM 호출을 끝낸 뒤 **저장만 별도 `@Transactional` 컴포넌트**(`PriceMoveCardWriter`와 같은 형태·같은 이유)에 넘긴다. **자기호출은 프록시를 타지 않으므로 같은 클래스의 private 메서드 애노테이션은 무효다** — 별도 클래스여야 한다. 저장 후 조회는 `UNIQUE(trade_id)`가 체결 1건당 1행을 강제한다.
  - `TradeFeedbackRepository`에 **체결 1건의 기존 서술 조회**를 더한다. 리포지토리 주석이 이 이슈 몫이라고 적어 둔 자리다.
  - **`narrativeStatus`는 항상 `READY`다** — 이 엔드포인트에 `UNAVAILABLE`이 **존재하지 않는다**(§C-4). LLM 실패·후검증 위반이면 `narrativeSource="TEMPLATE"`이고 서술이 비지 않는다. **보유 구간 극값이 없으면(`sameSessionCompleted=false`) 템플릿의 셋째 문장을 뺀다** — 앞 두 문장은 원장 수치라 그때도 성립하고 그래서 `READY` 보장이 유지된다(§템플릿 문장).
  - **LLM 실패가 응답을 막지 않는다.** 수치 요약과 파생 사실은 200으로 그대로 나가고, 타임아웃 처리 경로가 응답을 삼키지 않는지 확인한다(§실패 처리 — OpenAI 키 없음도 이 경로다).
  - 검증 — 단위(폴백 분기·프롬프트 입력 조립) + 고정 `Clock` + Testcontainers 통합(최초 조회 생성 → 재조회 시 재사용, 호출 횟수 단정). **완료 조건 2건 + 공통 1건이 이 항목 소유다.** 상태값 ⑤(LLM 실패에도 `READY`·`TEMPLATE`), LLM 실패에도 수치·파생 사실 200, 그리고 **8개 이슈 공통 조건인 원장 불변** — 회고 생성·조회 전후로 주문·체결·계좌·잔액·보유·손익 테이블의 행이 변하지 않고 쓰기가 `trade_feedbacks` 밖으로 나가지 않는다. 기존 `FeedbackBatchIntegrationTest`가 카드에 대해 같은 형태를 갖고 있다. **트랜잭션 경계는 "LLM이 느린 동안 커넥션을 쥐지 않는다"를 단정으로 옮기기 어려우므로 구조(별도 컴포넌트·`@Transactional` 위치)를 리뷰에서 보이게 남긴다.**

- [ ] **5. 서술 재생성 — 재생성 게이트 통과 후 1회와 누적 재시도 상한**

  매도 후 흐름과 집단 비교가 확정된 뒤 그 내용을 반영한 서술로 **1회** 갈아 끼운다.
  - 재생성 게이트는 §C-5다 — `postSellFlow`가 `READY`이고 **`peerComparison.status != NOT_YET`**이다. **`NO_EVENT`·`INSUFFICIENT_SAMPLE`도 확정으로 친다** — 보유 구간 카드가 0건이면 `price_move_peer_stats` 행이 애초에 안 생기는데 게이트를 "행 존재"로만 두면 **그 흔한 경우에 매도 후 흐름이 반영된 서술이 영원히 만들어지지 않는다.** 카드 0건은 예외도 로그도 없이 조용히 일어난다.
  - **`peerComparison.status`가 3번에서 상수 `NOT_YET`인 동안 이 게이트는 구조적으로 열리지 않는다.** 게이트 판정 로직과 그 테스트는 이 항목이 세우고, 7번이 실제 판정을 붙이면 열린다 — **게이트 조건을 6번 형편에 맞춰 느슨하게 고치지 않는다.** 테스트는 판정 협력자를 대체해 확정 상태를 재현한다.
  - 통과 시 **`narrative_finalized`를 `TRUE`로 바꾼다.** 그 뒤 조회에서는 재생성하지 않는다.
  - **재생성이 LLM 실패로 끝나면 기존 서술을 유지하고 `narrative_finalized`를 `FALSE`로 남긴다.** 재시도는 **체결 1건당 누적** `max-narrative-retry`회까지이며(`regeneration_attempts`) **날짜 단위로 리셋하지 않는다** — 실패 시 `generated_at`을 갱신하지 않아 날짜 기준이 애초에 성립하지 않는다(FEED-007·§C-7).
  - 상태 전이 메서드를 `TradeFeedback` 엔티티에 더한다 — 지금은 `create`만 있고 재생성·확정을 바꾸는 메서드가 없다. **저장은 4번이 세운 트랜잭션 경계 컴포넌트를 그대로 쓴다** — LLM 재호출이 트랜잭션 밖이어야 하는 이유가 같다.
  - 검증 — 고정 `Clock` + Testcontainers 통합(게이트 통과 후 첫 조회에서 재생성, 두 번째 조회에서 미재생성, 카드 0건 경로, 누적 상한). **완료 조건 3건이 이 항목 소유다.** 재생성 게이트 통과 후 1회, 누적 상한 초과 없음, 카드 0건인 매도도 재생성이 일어남이다. **카드 0건 픽스처가 이 항목의 핵심이다** — 카드가 있는 픽스처만 쓰면 "행 존재"로만 판정한 구현도 초록이고, 운영에서 가장 흔한 경우가 조용히 빠진다. **누적 상한은 실패를 상한 횟수 + 1회 재현해 마지막 호출이 실제로 일어나지 않는지 단정한다** — 상한 이하만 재현하면 리셋 버그를 잡지 못한다.

- [ ] **6. 문서 동기화 — `api-routes.md`·`api-contracts.md` 대조**

  /feature 마무리의 planner(**동기화 모드**)가 **실제 컨트롤러 매핑을 근거로** 두 문서를 맞춘다 (CLAUDE.md 규칙 7). 1·3번이 각자 커밋에서 자기 소절을 이미 갱신했으므로 여기서는 대조와 잔여 정리다.
  - `api-contracts.md`의 매도 회고 소절 제목에서 **`(계획)`이 걷혔는지** 확인한다. **표시가 남은 소절만 블랙박스 QA 근거에서 제외되므로** 남으면 이 API가 QA 대상에서 빠진다.
  - `api-contracts.md`에 **7번 머지 전까지 `counterfactuals`의 `returnRate`가 `null`이고 `peerComparison`이 항상 `NOT_YET`이라는 것**이 명시됐는지 확인한다. 계약 예시 JSON은 완성 형태이므로 **현재 동작과 어긋나는 자리를 문장으로 적어 두지 않으면 QA가 값 누락을 결함으로 올린다.**
  - `api-routes.md`의 **2차 계획 라우트 절이 비게 된다** — spec 012의 네 경로가 모두 구현되므로 그 절과 머리말("아래 1개 경로 …")을 정리한다. **개수를 고치지 않으면 다음 이슈가 그 문장을 근거로 센다**(#188이 같은 자리를 이미 한 번 고쳤다).
  - `priceMoves`·`sources`의 정렬 2차 키 문장이 계약에 남아 있는지 확인한다.
  - 두 문서는 **항상 같은 커밋에서 함께 맞춘다.**

## 완료 조건 소유

이슈 #208에 배정된 18건과 8개 이슈 공통 조건 1건이 어디서 검증되는지다 (`plan.md` §완료 조건 배정의 **6번 행** — 매도 회고 12 + 노출 게이트 ⑬~⑮ 3 + 상태값 ⑤ 1 + API 계약 2). **합계 19건이고 아래 표의 행 수와 같다.**

| # | 완료 조건 (`spec.md` §완료 조건) | 항목 |
|---|---|---|
| 1 | (매도 회고) 코인 매도 체결로 조회하면 400 — 빈 값 200을 돌려주지 않는다 | **1** |
| 2 | (매도 회고) 여러 lot에 배분된 매도의 매수 시각이 가장 이른 `executed_at`이다 (2개 lot 픽스처) | **1** |
| 3 | (매도 회고) 배분 lot이 서로 다른 원본 거래일이면 `sameSessionCompleted=false` | **1** |
| 4 | (매도 회고) `sameSessionCompleted=false`일 때 파생 사실·반사실·집단 비교가 전부 `null` | **2**(파생 사실·`priceMoves`) + **3**(`counterfactuals`·`peerComparison` 필드 자체) |
| 5 | (매도 회고) 파생 사실 정확 계산 — 극값, `buyToNewsMinutes` 부호, `minutesAfterBuy`·`minutesBeforeSell` | **2** |
| 6 | (매도 회고) 보유 구간 극값이 분봉 `close` 기준 (`high`/`low` 금지) | **2** |
| 7 | (매도 회고) `atClose`·`closePrice`가 그 거래일 "마지막 분봉"의 close | **3** |
| 8 | (매도 회고) `narrative_finalized=false` 서술이 재생성 게이트 통과 후 첫 조회에서 재생성, 두 번째는 안 함 | **5** |
| 9 | (매도 회고) 재생성 실패가 체결 1건당 누적 `max-narrative-retry`회를 넘지 않는다 | **5** |
| 10 | (매도 회고) 카드 0건인 매도도 재생성이 일어난다 | **5** |
| 11 | (매도 회고) LLM 호출이 실패해도 수치 요약과 파생 사실이 200 | **4** |
| 12 | (매도 회고) 투자일기 없이 매수·매도한 건도 정상 200 | **1** |
| 13 | (게이트 ⑬) 매도 후 흐름·반사실이 게이트 전에는 `NOT_YET`이고 가격 필드가 빈다 (직전·직후 두 시각) | **3** |
| 14 | (게이트 ⑭) 전날 매도 건을 다음 날 장중에 조회해도 `READY`를 유지한다 | **3** |
| 15 | (게이트 ⑮) `priceMoves`에도 카드 게이트가 걸린다 | **2** |
| 16 | (상태값 ⑤) `narrativeStatus`가 LLM 실패 시에도 `READY`이고 `narrativeSource="TEMPLATE"` — `UNAVAILABLE` 없음 | **4** |
| 17 | (API 계약) `tradeId` 미존재는 404, 타인 체결은 403, 매수·코인 체결은 400 | **1** |
| 18 | (API 계약) 응답 직렬화가 계약 필드 집합과 일치 — `priceMoveId`·`narrativeSource`·`buyAt`·`sellAt` 포함 | **3** |
| 19 | (공통) 원장 불변 — `trade_feedbacks` 밖에 쓰지 않고 수치는 원장에서 읽기만 한다 | **4** |

항목별 소유 건수는 **1번 5건 · 2번 3건 · 3번 4건 · 4번 3건 · 5번 3건 = 18건**이고, 여기에 2·3번이 나눠 갖는 4번 조건 1건을 더해 **19건**이다. 6번은 단독 소유가 없다 — 문서 대조다.

> **17번 조건의 문구 주의.** `spec.md` §완료 조건 API 계약 절의 원문은 "`instrumentId` 미존재는 404, 타인 체결은 403, 매수·코인 체결은 400"이라 **한 bullet이 두 엔드포인트에 걸쳐 있다.** `post-sell`에는 `instrumentId` 경로 변수가 없고 `tradeId`뿐이므로 **이 엔드포인트에서는 `tradeId` 미존재 404로 읽는다** — `docs/api-contracts.md`가 "`tradeId` 미존재는 404 `NOT_FOUND`"로 적어 둔 그대로다. **조건을 새로 만들거나 쪼개지 않는다.**

**인증 없이 호출하면 401**은 네 엔드포인트 공통이며 이 이슈가 만드는 엔드포인트에도 적용하되 **배정 건수에 넣지 않는다**(#180·#188과 같은 형태).

## 이 이슈에서 하지 않는 것

- **반사실 `returnRate` 수수료 재계산 · 반사실이 서술에 미포함 검증 · 모집단 재구성(`@DataJpaTest`) · 회원 식별자 없음** (`plan.md` 7번). **상태값 ⑥⑦**(`NO_EVENT`·`INSUFFICIENT_SAMPLE`)과 **장 마감 집계 배치**(배치 ⑥~⑧)도 7번이다. 경계는 위 §제약의 표대로 가른다
- **LLM 서술 생성·후검증·템플릿 폴백** (#147, 머지됨). `NarrativeService`를 **주입해 쓰기만** 한다
- **V13·엔티티·리포지토리 골격** (#160, 머지됨). **머지된 마이그레이션을 수정하지 않는다** (ADR-0004)
- **뉴스·공시 수집 파이프라인** (#167, 머지됨). **이미 저장된 기사를 읽기만** 한다
- **주식 변동 탐지·개장 전 배치·카드 조회 API** (#180, 머지됨). **`FeedbackBatchService`를 건드리지 않는다** — 이 이슈는 조회 경로만 만든다
- **종목 뉴스 요약·개장 전 브리핑 조회 API** (#188, 머지됨). `MarketSessionTimes`·`NewsItem`·`NewsItemTruncator`를 **재사용하고 다시 만들지 않는다**
- **코인 매도 회고** — FEED-007 각주가 3차로 미뤘다. **코인 체결을 400으로 거부하는 것만** 이 이슈다
- **코인 변동 탐지·실시간 감시·가격 스냅샷** (`plan.md` 8번). FEED-006의 코인 조회 분기도 8번이다
- **코인 질의어·제목 필터 개선** (#179). 수집 품질 문제다
- **조회 경로 LLM 호출량 계측.** `LlmCallStats`는 배치 스코프 전용이고 조회 호출을 세지 않는 것이 **의도된 동작**이다(#198). 필요하다고 판단되면 **별도 이슈로 제안한다**
- **투자일기 연계("계획 대비 실제 대조")** — FEED-007 각주가 범위 밖으로 뒀다. 나중에 `plan`·`planOutcome`을 **같은 응답에 추가**하면 되고 기존 필드는 유지되므로 계약이 깨지지 않는다
- **노출 게이트 ①~⑫, 배치 15건, 상태값 ①~④·⑥⑦, 매도 회고 4건, 문구 9건, 탐지 14건, 수집 6건** — 배정은 `plan.md` §완료 조건 배정이 정본이다
