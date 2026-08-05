# Tasks: 012 AI 피드백 — 이슈 #212 (반사실 시뮬레이션·집단 비교 구현)

> **이 문서는 이슈 #212의 범위만 담는다.** spec 012 전체(이슈 8개)의 분할은 `./plan.md`가 정본이고(§완료 조건 배정의 **7번 행**), 값·규칙은 `./spec.md` §확정값이 정본이다. 여기에 값을 다시 적지 않는다 — 상태값은 §C-4, 노출 게이트와 재생성 게이트는 §C-5, 패키지 배치와 클래스 이름은 §C-6, 설정 방침은 §C-7, 컬럼 타입은 §C-8, 재재생 근거는 §C-9, 반사실·집단 비교 계산식은 §반사실·집단 비교 계산, 응답 필드·예시 값은 `docs/api-contracts.md`의 "매도 직후 피드백 조회" 소절이다.
>
> **이슈 #208(머지됨, dev의 `14b8068`)이 6·7번 경계를 코드에 못박아 뒀다.** `PostSellFeedbackReader`의 "`─── plan.md 7번이 끼울 자리 ─────`" 주석, `PostSellFeedbackService`의 관련 javadoc, `docs/api-contracts.md` 매도 회고 소절의 "현재 구현 범위 (이슈 #208 머지 시점)" 인용 블록이 그 경계다. **이 이슈는 그 자리를 채우고, 마지막 항목에서 인용 블록을 걷어낸다.**
>
> 항목 하나 = implementer 1회 투입 = 커밋 1개 (`docs/specs/README.md`의 굵기 가이드). 테스트 레벨은 ADR-0003을 따른다 — 순수 계산은 단위, 리포지토리 파인더와 유니크 축은 `@DataJpaTest`, 배치·게이트 종단은 고정 `Clock` + Testcontainers 통합이다.

## 이 이슈 전체에 걸리는 제약

- **여기서 값을 새로 정하지 않는다.** 크론·임계값(5명 경계·수수료율)·상태값이 필요하면 위 참조를 읽는다. `application.yml`의 `spring.task.scheduling.pool.size` 주석처럼 **개수를 세는 자리**는 예외다 — §C-1이 "올릴 때마다 그 시점의 개수를 다시 센다"고 명시했으므로 지금 실제로 등록된 `@Scheduled` 개수를 세어 반영한다. 미리 정해진 숫자를 이 문서에 적지 않는다.
- **이미 있는 것을 다시 만들지 않는다.**

  | 이미 있는 것 | 이 이슈가 할 일 |
  |---|---|
  | `trade_feedbacks`·`price_move_peer_stats` 테이블 (V13), `PriceMovePeerStat` 엔티티, `PriceMovePeerStatRepository` | **머지된 마이그레이션을 고치지 않는다**(ADR-0004). 새 컬럼이 필요해 보이면 코드를 먼저 만들지 말고 `spec.md` §C-8을 확인한 뒤 오케스트레이터에게 보고한다. `PriceMovePeerStatRepository`는 지금 조회 메서드가 없다 — 배치 저장용·조회용을 **필요한 만큼만** 더한다 |
  | `PostSellFeedbackReader.buildCounterfactuals`·`peerComparisonNotYet` 및 응답 조립 지점의 "7번이 끼울 자리" 주석 | 그 주석이 적어 둔 규칙 그대로 `returnRate`와 `peerComparison`을 채운다. 주석을 지우고 그 자리에 실제 코드를 넣는다 |
  | `NarrativeService.resolvePostSellNarrative` + `PostSellPromptDto` | **손대지 않는다.** `PostSellPromptDto`에는 반사실 필드가 애초에 없다(§왜 반사실은 AI 문장에 넣지 않는가) — 필드를 추가하지 않는다. 집단 비교는 이미 nullable로 열려 있으므로 값이 흘러들어가는 것만 확인한다 |
  | 재생성 게이트 판정(`postSellFlow.status == READY && peerComparison.status != NOT_YET`, `PostSellFeedbackService`) | **게이트 조건을 고치지 않는다.** `peerComparison.status`가 상수 `NOT_YET`에서 실제 판정으로 바뀌는 순간 이 게이트는 한 줄도 안 고쳐도 열린다 — 그것이 #208이 설계한 경계다 |
  | `StockReplayService.getFullDayCandles`·`getSourceTradingDate` (#180) | 재사용한다. 새 조회 메서드를 `market`에 만들지 않는다 |
  | `portfolio`의 `TradeAllocationRepository`·`HoldingLotRepository` | **모집단 재구성에 필요한 조회만 더한다.** `feedback`은 이 리포지토리들을 직접 주입하지 않고 서비스를 경유한다(§C-6) |
- **`holdings` 테이블로 모집단을 구하지 않는다.** 현재 상태만 갖는 가변 행이라 과거 시점 스냅샷이 없다 — §반사실·집단 비교 계산의 lot·배분 재구성 식을 그대로 따른다.
- **LLM 호출 경계를 건드리지 않는다.** 이 이슈는 새 LLM 호출을 추가하지 않는다 — 반사실은 순수 계산이고 집단 비교는 배치가 만든 값을 읽기만 한다. `PriceMoveCardWriter`·저장 전용 컴포넌트 분리 방식을 그대로 따르되 새로 트랜잭션 경계를 설계할 필요는 없다.
- **`FeedbackSchemaConstraintsTest`의 기대 맵을 건드리지 않는다.** 이 이슈는 새 컬럼을 만들지 않는다(§C-8이 이미 `price_move_peer_stats`를 완성해 뒀다) — 맵이 바뀌어야 하면 스키마를 잘못 건드린 신호다.
- **원장에 쓰지 않는다.** 이 이슈가 쓰는 테이블은 `price_move_peer_stats` 하나뿐이고 그 밖은 전부 읽기다.

## 작업 항목

- [x] **1. 반사실 수익률 — `counterfactuals` 3종의 수수료 재계산**

  `PostSellFeedbackReader.buildCounterfactuals`가 채워 둔 `price`·`at`은 그대로 두고 `returnRate`만 계산해 넣는다. 6·7번 경계표가 정확히 이 필드에서 갈린다.
  - 완료 조건(§완료 조건 "매도 회고" 절) — "반사실 3개 시나리오의 수익률이 **수수료를 다시 계산해** 산출된다. 수수료는 `FLOOR(매도금액 × 시장별 요율)`이며, 반올림하면 `api-contracts.md`의 예시 값과 어긋난다." 시나리오 3종의 정의·수익률 산출식은 spec.md §반사실·집단 비교 계산이 정본이다.
  - 완료 조건 — "반사실 값이 **AI 서술 문자열에 포함되지 않는다** — 구조화 필드로만 나간다." `PostSellPromptDto`에 필드를 추가하지 않는 것만으로 구조적으로 성립하지만, 프롬프트 조립 지점을 단정하는 테스트로 실제로 막혀 있는지 확인한다.
  - **함정 — 반올림과 내림을 구분하지 못하는 픽스처.** 가격이 딱 나누어지는 픽스처만 쓰면 `FLOOR`와 일반 반올림(`HALF_UP` 등)이 같은 결과를 낸다. **매도수수료가 소수로 끝나는 가격·수량 조합**을 픽스처에 넣어 `api-contracts.md`의 예시 값과 정확히 일치하는지, 반올림 구현이었다면 그 값이 어긋나는지 확인한다.
  - `atFirstMoveAfterBuy`처럼 `price`가 `null`인 시나리오(보유 구간에 카드가 없음)는 `returnRate`도 `null`이다 — 이미 있는 `null` 전파를 반사실 계산에서 다시 깨지 않는다.
  - 검증 — 단위(3개 시나리오 수익률 산출, `FLOOR` 경계, `price=null`이면 `returnRate=null`) + `@WebMvcTest` 또는 서비스 단위(AI 서술에 반사실 수치 문자열이 없음을 단정).

- [x] **2. 모집단 재구성 — `portfolio` 조회 메서드 신설**

  카드 `windowEnd`를 서비스 날짜에 붙인 절대 시각 `T` 기준으로 "그 시점에 해당 종목을 보유 중이던 회원" 집합을 lot·배분으로 복원한다. §C-6이 이 조회를 `portfolio` 소유로 못박아 뒀다("특정 시점 보유자 집계 (회원 식별자 없는 반환)").
  - 계산식은 spec.md §반사실·집단 비교 계산의 `[집단 비교]` 블록이 정본이다 — `holding_lots.original_quantity`(불변) 합에서 `trade_allocations.allocated_quantity`(그 배분이 속한 매도 체결의 `executed_at <= T`인 것만) 합을 뺀 값이 회원별로 양수인지를 본다. `remaining_quantity`(가변)를 쓰면 안 된다 — 현재 상태로 되돌아가 `holdings` 문제가 그대로 재현된다.
  - 반환값은 **회원 식별자를 포함하지 않는다.** 완료 조건(§완료 조건 "매도 회고" 절) — "집단 비교 응답에 회원 식별자가 어떤 형태로도 포함되지 않는다." 집계된 크기·비율·중앙값만 밖으로 나간다.
  - **`feedback`은 `portfolio`의 repository를 직접 주입하지 않고 서비스를 경유한다**(§C-6). 없는 조회만 더하고 기존 `TradeAllocationRepository`·`HoldingLotRepository`의 메서드는 재사용한다.
  - 완료 조건(§완료 조건 "매도 회고" 절, `@DataJpaTest`) — "**카드 시점에 보유 중이었으나 그 뒤 전량 매도한 회원이 모집단에 포함된다** — `holdings`를 그대로 조회하면 정확히 이 회원들이 빠져 `soldWithin30MinRate`가 과소 집계된다."
  - **함정 — 전량 매도 회원 픽스처가 빠지면 오탐.** 보유 중인 회원만 픽스처에 넣으면 `holdings`를 그대로 조회한 구현과 lot·배분으로 재구성한 구현이 **같은 답을 낸다.** 반드시 "T 시점에는 보유했지만 그 뒤(집계 시점 전에) 전부 팔아 지금은 `holdings`에 없는" 회원을 픽스처에 포함하고, 그 회원이 모집단 크기에 잡히는지로 구분한다.
  - 검증 — `@DataJpaTest` 하나로 위 함정 픽스처를 고정한다. 정렬·상한은 이 조회에 없다(개인 목록이 아니라 집계 하나다).

- [ ] **3. `PeerStatsBatchService` 신설 — 장 마감 집단 비교 확정 집계**

  §C-6이 이름을 못박아 둔 신설 서비스다. 카드별로 2번 항목의 모집단 조회를 부르고 §반사실·집단 비교 계산의 세 지표(모집단 크기·30분 내 매도 비율·매도까지 걸린 시간의 중앙값)를 계산해 `price_move_peer_stats`에 저장한다.
  - 크론은 §C-1 `feedback.batch.peer-stats-cron`이다. `FeedbackBatchProperties`(record)에 새 필드를 추가하고 yml·`@DefaultValue` 양쪽에 값을 두는 기존 방침(§C-7)을 따른다 — `FeedbackBatchProperties`의 주석이 "그 스케줄을 실제로 더하는 이슈가 함께 추가한다"고 이 이슈를 가리키고 있다. `@Scheduled`에 `zone = "Asia/Seoul"`을 반드시 붙인다(§C-1).
  - **`spring.task.scheduling.pool.size`를 자기 몫만큼(신설 크론 1개) 올린다.** `application.yml`과 `application-crypto-real.yml` 두 곳의 "`@Scheduled` 작업은 N개다" 주석 개수 계산을 함께 갱신한다(§C-1 — "한쪽만 고치면 다음 이슈가 어느 쪽을 근거로 셀지 갈린다"). 값을 미리 적지 않고 **그 시점에 실제로 등록된 개수를 센다.**
  - 다른 개장 전 배치와 같은 패턴을 따른다 — 재생세션이 `READY`가 아니면 아무것도 하지 않는다. **배치를 건드리지 않는 기존 제약은 `FeedbackBatchService`(개장 전 배치) 한정이다** — 이 항목은 새 배치를 만드는 것이므로 해당하지 않는다.
  - 완료 조건(§완료 조건 "배치" 절) — "장 마감 배치가 `price_move_peer_stats`에 확정 집계를 저장하고, 두 번 실행돼도 중복되지 않는다." 중복 방지는 `UNIQUE(price_move_event_id, service_date)`(§C-9)로 이미 걸려 있으므로 배치는 그 제약과 충돌하지 않게 짠다(중복 저장 시도를 조용히 무시하거나 사전에 존재 여부를 확인한다).
  - 완료 조건 — "**같은 원본 거래일 재재생 시 집계가 서비스 날짜별로 따로 쌓인다** — 덮어쓰면 첫날 매도자의 통계가 사라진다(§C-9)."
  - **함정 — 재재생 픽스처 없이는 서비스 날짜 분리를 검증할 수 없다.** 같은 카드를 한 서비스 날짜에서만 집계하는 픽스처로는 `UNIQUE(price_move_event_id)` 단독(서비스 날짜 없이)로 잘못 짠 구현도 초록이다. **같은 원본 거래일을 두 서비스 날짜에 재생하는 픽스처**로 두 행이 각각 쌓이는지 확인한다.
  - 검증 — 고정 `Clock` + Testcontainers 통합(장 마감 시각에 배치 실행 → 행 생성, 두 번 실행 → 중복 없음, 재재생 두 서비스 날짜 → 행 2개).

- [ ] **4. `peerComparison` 상태 판정 — `PriceMovePeerStatRepository` 조회와 응답 채우기**

  `PostSellFeedbackReader`의 "7번이 끼울 자리" 주석이 적어 둔 순서대로 `peerComparison`을 완성한다. **이 항목이 끝나면 서술 재생성 게이트가 코드 수정 없이 열린다** — 게이트 조건 자체는 손대지 않는다(위 §제약).
  - `PriceMovePeerStatRepository`에 "그 체결의 서비스 날짜 행만" 보는 조회 메서드를 더한다(§C-9). 지금은 조회 메서드가 없다.
  - 판정 순서는 §C-4가 정본이다 — `NO_EVENT`가 1순위(기준 카드 자체가 없으면 확정 집계 행이 애초에 없다), 그다음 확정 집계 행의 `holderCount < 5`면 `INSUFFICIENT_SAMPLE`, 그 외 `READY`. `NOT_YET`은 이 항목이 만들지 않는다 — 배치가 아직 안 돈 경우이므로 확정 집계 행이 없을 때의 기본값이다.
  - 완료 조건(§완료 조건 "상태값" 절) — "보유 구간에 카드가 0건이면 `peerComparison.status="NO_EVENT"`이고 전 필드가 `null`이다."
  - 완료 조건 — "모집단 5명 미만이면 `INSUFFICIENT_SAMPLE`이고 모집단 지표 3종이 `null`, `yourMinutesToSell`은 채워진다." `yourMinutesToSell`은 모집단 통계가 아니라 본인 값이므로 `INSUFFICIENT_SAMPLE`에서도 채운다(§반사실·집단 비교 계산).
  - **함정 — `INSUFFICIENT_SAMPLE` 경계가 5명 정확히 어느 쪽인지.** `holderCount = 5`(경계 포함, `READY`)와 `holderCount = 4`(경계 미달, `INSUFFICIENT_SAMPLE`) **두 픽스처를 모두 만든다.** 한쪽만 두면 `<`와 `<=`를 뒤바꾼 구현이 통과한다.
  - 완료 조건(2번 항목과 공유) — "집단 비교 응답에 회원 식별자가 어떤 형태로도 포함되지 않는다." 이 항목은 `PriceMovePeerStat` 엔티티 자체가 회원 식별자를 안 가지므로 구조적으로 성립하지만, 응답 DTO 매핑에서 새 필드를 추가하며 실수로 식별자를 끼워 넣지 않았는지 확인한다.
  - 검증 — 단위(판정 순서 4가지 분기, 5명/4명 경계) + `@DataJpaTest`(조회 메서드가 서비스 날짜로 정확히 걸러지는지, 같은 이벤트의 다른 서비스 날짜 행을 섞어 확인) + `@WebMvcTest`(직렬화).

- [ ] **5. 재생성 게이트 테스트를 실제 `price_move_peer_stats` 픽스처로 전환**

  `PostSellFeedbackRegenerationIntegrationTest`가 지금 `@MockitoBean PostSellFeedbackReader`로 `peerComparison.status`를 확정 상태로 대역 처리하고 있다(파일 주석에 "3번 항목이 그 값을 상수 `NOT_YET`으로 두어 이 이슈 범위에서는 구조적으로 열리지 않는다"고 적혀 있다 — 그 "3번 항목"이 #208의 3번이고 지금은 이 이슈 4번이 실제 판정을 붙였다). **mock을 걷어내고 실제 `price_move_peer_stats` 행으로 게이트를 연다.**
  - `@MockitoBean private PostSellFeedbackReader postSellFeedbackReader;`와 그 스텁 설정을 제거한다. 대신 각 테스트가 필요로 하는 `peerComparison` 확정 상태(`NO_EVENT`·`INSUFFICIENT_SAMPLE`·`READY`)를 4번 항목이 만든 실제 판정 경로로 재현한다 — 카드 0건 테스트는 카드를 안 만들면 되고, `INSUFFICIENT_SAMPLE` 테스트는 `PriceMovePeerStatRepository`에 `holderCount < 5`인 행을 직접 저장해 재현한다(3번 항목의 배치 전체를 다시 돌릴 필요는 없다).
  - 기존 완료 조건 3건(게이트 통과 후 1회, 누적 상한, 카드 0건 재생성)의 시나리오와 단정은 그대로 유지한다 — **이 항목은 대역을 실제로 바꾸는 것이고 새 완료 조건을 만들지 않는다.**
  - 파일 상단 주석의 "**PostSellFeedbackReader를 mock으로 대체한다**" 단락을 지우거나 갱신한다 — 그 이유(게이트가 구조적으로 안 열림)가 이 이슈로 해소됐다.
  - 검증 — 기존 3개 테스트가 실제 픽스처로도 여전히 통과하는지, 그리고 **mock을 되돌렸을 때(즉 게이트를 "행 존재"로만 판정하는 구현으로 되돌렸을 때) 카드 0건 테스트가 실제로 빨간지** 확인한다 — 그래야 이 전환이 검증력을 늘렸다고 말할 수 있다.

- [ ] **6. 원장 불변 — 8개 이슈 공통 조건의 확장**

  완료 조건(공통) — "카드 생성·조회 전후로 주문·체결·계좌·잔액·보유·손익 원장이 변하지 않는다." 기존 `FeedbackBatchIntegrationTest` 계열이 쓰는 형태(배치·조회 전후 원장 테이블 스냅샷 대조)를 그대로 확장해 **이 이슈가 새로 만드는 쓰기 경로 둘**을 덮는다.
  - `PeerStatsBatchService` 실행 전후로 `orders`·`trades`·`accounts`·`holdings`·`holding_lots`·`trade_allocations` 행이 변하지 않는다 — 이 배치의 유일한 쓰기 대상은 `price_move_peer_stats`다.
  - `peerComparison`이 포함된 매도 회고 조회 전후로도 같은 테이블들이 변하지 않는다 — 2번 항목의 모집단 재구성 조회는 읽기 전용이어야 한다.
  - 검증 — 고정 `Clock` + Testcontainers 통합. 기존 원장 불변 테스트의 스냅샷 대조 패턴을 재사용한다.

- [ ] **7. 문서 동기화 — `api-contracts.md` 인용 블록 제거·`prd.md` §3 갱신**

  `docs/api-routes.md`는 **대상이 아니다** — 이 이슈는 새 컨트롤러를 만들지 않고 기존 `GET /api/ai/post-sell/{tradeId}` 응답의 필드를 채우기만 한다.
  - `docs/api-contracts.md` 매도 회고 소절의 "**현재 구현 범위 (이슈 #208 머지 시점)**" 인용 블록을 통째로 걷어낸다(이슈 #212 본문·spec.md 6·7번 경계표가 이 이슈에서 걷어내라고 명시했다). 예시 JSON의 `returnRate`·`peerComparison`이 이제 실제로 채워지는 값이라는 것과 어긋나지 않는지 확인한다.
  - `docs/prd.md` §3 "구현 현황"의 **FEED-010(반사실 시뮬레이션)·FEED-011(집단 비교)** 행을 완료로 갱신하고 근거 칸에 이 PR 번호를 적는다(CLAUDE.md 규칙 10). 아직 행이 없으면 새로 추가한다.
  - 두 문서는 **항상 같은 커밋에서 함께 맞춘다.**

## 완료 조건 소유

이슈 #212에 배정된 9건(`plan.md` §완료 조건 배정의 **7번 행** — 매도 회고 4 + 상태값 2 + 배치 3)과 8개 이슈 공통 조건 1건, 합계 10건이다.

| # | 완료 조건 (`spec.md` §완료 조건) | 항목 |
|---|---|---|
| 1 | (매도 회고) 반사실 3개 시나리오의 수익률이 수수료를 다시 계산해 산출된다 | **1** |
| 2 | (매도 회고) 반사실 값이 AI 서술 문자열에 포함되지 않는다 | **1** |
| 3 | (매도 회고, `@DataJpaTest`) 카드 시점에 보유 중이었으나 그 뒤 전량 매도한 회원이 모집단에 포함된다 | **2** |
| 4 | (매도 회고) 집단 비교 응답에 회원 식별자가 어떤 형태로도 포함되지 않는다 | **2**(모집단 조회 자체) + **4**(응답 매핑) |
| 5 | (상태값 ⑥) 보유 구간에 카드가 0건이면 `peerComparison.status="NO_EVENT"`이고 전 필드가 `null`이다 | **4** |
| 6 | (상태값 ⑦) 모집단 5명 미만이면 `INSUFFICIENT_SAMPLE`이고 모집단 지표 3종이 `null`, `yourMinutesToSell`은 채워진다 | **4** |
| 7 | (배치 ⑥) 장 마감 배치가 `price_move_peer_stats`에 확정 집계를 저장하고, 두 번 실행돼도 중복되지 않는다 | **3** |
| 8 | (배치 ⑦) 같은 원본 거래일 재재생 시 집계가 서비스 날짜별로 따로 쌓인다 | **3** |
| 9 | (배치 ⑧) 집단 비교 배치 전에 조회하면 `peerComparison.status="NOT_YET"`이고 서술이 재생성되지 않는다 | **4**(상태값 판정) + **5**(게이트 종단 재확인) |
| 10 | (공통) 원장 불변 | **6** |

**5번 항목은 단독 소유 조건이 없다** — 기존 완료 조건(#208의 재생성 게이트 3건)의 검증 방식을 바꾸는 항목이다. **7번 항목도 단독 소유가 없다** — 문서 대조다.

## 이 이슈에서 하지 않는 것

- **매도 회고 나머지 12건·노출 게이트 ⑬~⑮·상태값 ⑤·API 계약 2건** (`plan.md` 6번, #208, 머지됨). `PostSellFeedbackResponse`의 필드를 새로 만들지 않는다 — 이미 있는 `counterfactuals`·`peerComparison` 자리에 값을 채우기만 한다.
- **`postSellFlow`·`counterfactuals`의 `price`·`at`, 게이트 판정** (#208, 머지됨). 이미 채워져 있다 — 다시 만들지 않는다.
- **LLM 서술 생성·후검증·템플릿 폴백** (#147, 머지됨). `NarrativeService`를 건드리지 않는다.
- **V13·엔티티·리포지토리 골격** (#160, 머지됨). `price_move_peer_stats` 테이블·엔티티는 이미 있다 — 조회·저장 메서드만 더한다.
- **뉴스·공시 수집 파이프라인** (#167, 머지됨). 이미 저장된 카드·기사를 읽기만 한다.
- **주식 변동 탐지·개장 전 배치·카드 조회 API** (#180, 머지됨). `FeedbackBatchService`(개장 전 배치)를 건드리지 않는다 — `PeerStatsBatchService`는 별도 신설이다.
- **종목 뉴스 요약·개장 전 브리핑 조회 API** (#188, 머지됨).
- **코인 매도 회고·코인 변동 탐지·실시간 감시·가격 스냅샷** (`plan.md` 8번). FEED-010·011은 2차에서 주식 전용이다(spec.md FEED-007 각주).
- **조회 경로 LLM 호출량 계측.** `LlmCallStats`는 배치 스코프 전용이고 이 이슈는 새 LLM 호출을 추가하지 않으므로 애초에 대상이 아니다.
- **노출 게이트 ①~⑫, 상태값 ①~⑤, 배치 나머지 12건, 매도 회고 나머지 12건, 문구 9건, 탐지 14건, 수집 6건** — 배정은 `plan.md` §완료 조건 배정이 정본이다.
