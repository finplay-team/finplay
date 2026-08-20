# Tasks: 튜토리얼 손절·익절 프리셋 — CRYPTO 자동 예약(OCO) 연동

> 이 spec은 `../041-tutorial-market-scenario`와 같은 코드를 건드린다. **배포 순서는
> `../041-tutorial-market-scenario/tasks.md` §교차 순서가 정본이다.** 아래 항목의 `SNAP-1`·`SNAP-2`가
> `SNAP-1`·`SNAP-1b`·`SNAP-2`가 전체에서 가장 먼저 나가야 하고, 3~7번은 041의 tick 작업 뒤에 온다.

## 스키마 선행 작업 — 재진입 snapshot을 위한 2단계 배포

`practice_risk_snapshots`의 `UNIQUE(attempt_id, run_number)`가 한 실행 세대에 snapshot 하나만 허용한다.
041의 재진입 흐름은 매수가 두 번 생기므로 이 제약을 교체해야 하는데, 제약 삭제는 파괴적 변경이라 한
배포에 담을 수 없다(ADR-0021 §결정 7).

**세 항목 모두 뒤따르는 기능 코드가 의존하지 않는다. 그래서 전체 작업의 맨 앞에 둔다** — 뒤로 미루면 "041의
재진입이 이것보다 먼저 나가면 깨진다"는 순서 의존을 사람이 계속 챙겨야 한다.

- [x] **SNAP-1** — 마이그레이션: `practice_risk_snapshots`에 `entry_sequence INT NOT NULL DEFAULT 1`
  추가 + `uk_practice_risk_snapshots_attempt_run_seq(attempt_id, run_number, entry_sequence)` 추가.
  **기존 UNIQUE는 그대로 둔다.** 코드 변경 없음.
- [x] **SNAP-1b** — `PracticeRiskSnapshotRepository.findByAttemptIdAndRunNumber`를
  `findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc`로 바꾸고 **호출 지점 6곳**의 의미를 각각 판정한다.
  **판정표는 plan §제약 교체만으로는 부족하다에 있다** — 관찰 필터 기준선만 "첫 진입"이고 나머지는 최신
  진입이다. 이 하나를 틀리면 재매수 순간 3단계가 미완료로 되돌아간다(이슈 #420과 같은 유형). **`SNAP-2`보다 먼저 끝나야 한다** — 제약만 풀고 쿼리를 두면 재진입 직후
  조회·복기·재시작·완료가 `IncorrectResultSizeDataAccessException`으로 죽는다.
- [x] **SNAP-2** — 마이그레이션: 기존 `uk_practice_risk_snapshots_attempt_run` 삭제 (V49 · PR #458 머지 완료).
  **별도 PR·별도 배포다.** 새 UNIQUE가 같은 보호를 하므로(코드가 항상 `entry_sequence = 1`을 쓴다)
  이 창에서 중복 snapshot이 생길 수 없다.

## 작업 항목

- [x] **1. 프리셋 상수와 계산** — `ExitPreset` enum(`CAUTIOUS` 2/3, `BALANCED` 3/5, `RELAXED` 5/8,
  기본값 `BALANCED`) + `ReferencePriceCalculator.calculateFromPercent` 연결.
  **테스트**: `BALANCED`로 계산한 `stopLossPrice`·`takeProfitPrice`가 현행 `entryPrice × 0.97`·`× 1.05`와
  **정확히 같은 값**임(EXITPRESET-002). 041 대본을 읽어 세 프리셋의 도달 부등식 판정.
  > **구현에서 정한 것 (이슈 #470).**
  > - **판정 위치.** "041 tasks 1번과 같은 대상을 반대편에서 검사한다"를 그대로 하면 같은 조건이 두 곳에
  >   남아 한쪽만 고쳐도 초록이 유지된다. 프리셋이 걸린 부등식은 전부
  >   `ExitPresetScenarioReachabilityTest`(042)로 옮기고 041 테스트에는 대본 내부 성질만 남겼다.
  > - **비율 단위는 퍼센트 수다**(3%는 `3`). `calculateFromPercent`가 내부에서 100으로 나누고
  >   `exit_plans.stop_loss_rate DECIMAL(7,4)`도 같은 단위다. 분수로 넘기면 예외 없이 100배 틀린다.
  > - **표시 이름(조심스럽게·보통·느긋하게)은 만들지 않았다.** plan §API 계약의 `availableExitPresets`가
  >   식별자·비율만 내려보내므로 서버가 쓰지 않는 문구를 열거형에 두지 않았다. **필요하면 3번에서 응답
  >   계약과 함께 정한다.**
  > - `calculateFromPreset`이 체결가를 scale 8로 **먼저** 반올림한다. 현행 코드가 그렇게 하고 있어
  >   EXITPRESET-002의 "정확히 같은 값"이 그 위에 서 있다 — 4번에서 `trade.getPrice()`를 그대로 넘겨도
  >   안전하다.

- [x] **2. 스키마와 엔티티** — 마이그레이션: `practice_attempts.exit_preset`,
  `practice_risk_snapshots.exit_preset`, `exit_plans.practice_attempt_id`·`practice_attempt_run_number`
  (+ `orders`와 같은 모양의 CHECK: 둘 다 null이거나 둘 다 non-null, run > 0). 대응 엔티티 필드.
  > **구현에서 정한 것 (이슈 #470, V51).** plan에 없는 것을 둘 더했고 근거는 V51 주석에 있다 —
  > ① `exit_preset` 값 집합 CHECK(V38이 `market`·`status`를 같은 방식으로 막는다),
  > ② `idx_exit_plans_practice_attempt_run_status`(6번의 PENDING 예약 조회용).
  > **인덱스를 FK보다 먼저 만든다** — 선두 컬럼이 `practice_attempt_id`라 FK가 이 인덱스를 그대로 쓴다.
  > (`orders`는 FK → 인덱스 순서인데도 여분 인덱스가 없다 — MySQL 8.4 실측: FK는 있고 같은 이름의 인덱스만
  > 없다. 이 버전이 뒤늦게 생긴 적합한 인덱스를 보고 자동 인덱스를 정리한다는 뜻이며, 순서를 명시한 것은 그
  > 정리 동작에 기대지 않기 위해서다. 단언은 `exit_plans` 쪽에만 있고 `orders`에는 스키마 테스트가 없다.)
  > `restart()`의 `exit_preset` 초기화도 여기서 함께 했다(6번에 적혀 있으나 필드를 만드는 자리가 여기다).

- [x] **3. 선택 API와 잠금** — `PUT /api/education/practice/attempts/{market}/exit-preset`.
  **`PracticeAttemptResponse`는 `047`(TUTORIAL-CASH-ISOL-011)도 건드린다** — 튜토리얼 계좌 잔고 필드가
  추가되므로 충돌을 예상하고 먼저 머지된 쪽에 맞춰 rebase한다.
  > **구현에서 정한 것 (이슈 #477).** plan §오류 계약은 "종목 선택 전 프리셋 변경 → 409"로 읽히는데
  > 구현은 200을 반환한다. 종목 선택 전에는 보유가 있을 수 없어 잠금 조건("지금 들고 있는가")이 성립하지
  > 않기 때문이고, 프리셋은 실행 세대에 귀속되므로 미리 골라 두는 것이 해롭지 않다. `docs/api-contracts.md`가
  > 구현 쪽으로 적혀 있다.
  **잠금 기준은 "현재 실행 세대의 순보유수량 == 0"이다** — 매수 전과 재진입 대기 중에는 허용, 보유 중에는
  409 `PRACTICE_STEP_LOCKED`. `PracticeAttemptResponse`에 `selectedExitPreset`·`exitPresetLocked`·
  `availableExitPresets` 추가.
  **테스트**: `@WebMvcTest` + 단위 — 보유 중 거부, **매도 후 다시 허용**, 정의 밖 값 400, 완료 후 409.

- [x] **4. 매수 체결에 프리셋 반영 (예약 없이) + 진입당 1회 가드** — `createFirstBuyRiskSnapshot`을
  `createRiskSnapshotOnBuyFill`로 바꾸고 attempt의 프리셋과 `entry_sequence`(기존 수 + 1)를 반영.
  **직전 순보유수량이 0일 때만 snapshot을 만든다**(EXITPRESET-020) — 보유 중 추가 매수는 체결만 되고
  기준선이 움직이지 않는다. 여기까지는 "기존 동작 + 값이 달라짐"이라 회귀만 보면 된다. 예약은 5번에서 붙인다.
  **테스트**: 미선택 사용자의 결과가 이 기능 도입 전과 동일함, `exit_preset`이 null인 기존 snapshot이
  `BALANCED`로 해석됨.

- [x] **5. CRYPTO 자동 예약 생성** — `ExitPlanCreateCommandDto.practice(...)` 팩토리 추가(attempt·run
  귀속), `market == CRYPTO`일 때만 같은 트랜잭션에서 `ExitPlanCreationService.create` 호출.
  `exitPriceType = PERCENT`로 비율을 함께 저장. **공용 엔진(`ExitPlanCreationService`)을 직접 부른다** —
  호출부 `ExitPlanService`에는 `047`이 넣은 샌드박스 차단이 있고, 그 차단은 이 경로를 막지 않도록
  의도적으로 호출부에만 있다(plan §자동 예약 생성). **baseline을 041의 대본 canonical price로 주입한다** —
  엔진 기본 경로는 사인파 항시 시세를 읽는다. **STOCK은 snapshot까지만**(EXITPRESET-018).
  > **1·2번이 남긴 함정 (이슈 #470).** `ExitPricePolicy`의 PERCENT 경로는 체결가를 정규화하지 않고 그대로
  > 곱하는데, snapshot을 만드는 `ReferencePriceCalculator.calculateFromPreset`은 scale 8로 먼저 반올림한다.
  > **예약에 넘기는 체결가도 같은 scale 8 값이어야** 화면의 기준선과 실제 체결선이 scale 9 이하 자리에서
  > 갈리지 않는다. 두 경로가 같은 값을 내는지 통합 테스트에서 함께 확인해라.
  >
  > **귀속 컬럼의 애플리케이션 레벨 검증을 넣을지 여기서 정한다 (PR #471 리뷰 참고).** 2번은 매핑과 DB CHECK만
  > 만들었고, `ExitPlan`에는 `Order.createForPracticeAttempt`가 쓰는
  > `validatePracticeAttemptAttribution`(둘 다 non-null·양수) 같은 팩토리 검증이 없다. 값을 넣는 것이 이
  > 항목이므로, `ExitPlan`의 생성 팩토리를 고칠 때 `Order`와 대칭으로 검증을 둘지 함께 판단해라 —
  > 두지 않으면 이 불변식을 지키는 것은 DB CHECK 하나뿐이고, 위반이 트랜잭션 커밋 시점에야 드러난다.
  귀속 컬럼·baseline 주입 때문에 `ExitPlan` 생성 팩토리와 `newExitPlan`도 함께 바뀐다.
  **테스트**: 통합 — snapshot과 예약이 같은 트랜잭션에서 생기고 **실패 시 둘 다 남지 않음**.

- [x] **6. tick 정산·재시작 정리·수동 매도 공존** — 세 가지를 한 덩어리로 본다. 전부 예약 원장이 얽힌다.
  - `PracticeOrderSettlementService.settleCurrentRun`에 `exitPlanFillService.fillIfPending(id, price)`
    루프 추가 + `ExitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)` 신규.
    순서는 지정가 → OCO로 고정.
  - `PracticeRunRestartOrderService`에 예약 취소를 **주문 취소보다 먼저** 넣는다(예약 수량이 남아 있으면
    보상 매도가 `availableQuantity` 부족으로 실패한다). `exit_preset`도 null로 초기화.
    **`047`이 같은 트랜잭션 끝에 튜토리얼 계좌 리셋을 추가해 뒀다(TUTORIAL-CASH-ISOL-006).
    예약 취소가 그 리셋보다 앞이어야 한다.**
  - 튜토리얼 샘플 매도 주문 접수 전에 현재 run의 PENDING 예약을 같은 트랜잭션에서 전부 취소.
  **테스트**: 통합 — 대본이 손절선을 지날 때 체결되고 익절 예약이 자동 취소됨, 같은 가상 분 중복 tick이
  중복 체결을 안 만듦, 전량 예약 상태에서 시장가 매도가 정상 체결됨, 재시작이 예약 수량을 정확히 1회
  반환하고 다른 실행 세대·일반 경로 예약을 건드리지 않음.
  **정산·취소 뒤에는 `Holding`을 반드시 재조회한다** — 두 서비스가 `detach`를 호출하므로 같은 트랜잭션의
  기존 인스턴스를 재사용하면 수량 변경이 조용히 유실된다(plan 잔여 위험).

- [~] **7. 매도 원인과 완료 대조** (sellCause 완료, 진입별 배열은 041 6번으로 이관) — `PracticeTradeResultResponse`에 `sellCause`
  (`STOP_LOSS`\|`TAKE_PROFIT`\|`MANUAL`) 추가하고, **진입별 대조를 배열로 제공**한다(041 SCENARIO-019b).
  현재 `PracticeAttemptEvidenceService`가 `tradeSummary.firstSellTrade()` 하나만 쓰므로, 재진입하면 완료
  화면에 2막 손절만 뜨고 3막 익절이 사라진다. `exit_plans.triggered_order_id` 역참조로 판정한다.
  나머지 대조 값(`sellPrice`·`realizedPnl`·`returnRate`·`sellVerdict`)은 이슈 #421로 이미 있다. 이 배열이 041의 "안 팔았다면" 선 재료도 겸한다.
  `docs/prd.md` §3에 `EXITPRESET-001~020` 행 추가.
  > **구현에서 정한 것 (이슈 #477).**
  > - **`sellCause`는 넣었다.** `exit_plans.triggered_order_id` 역참조로 판정하며, 예약이 가리키지 않는
  >   매도는 전부 `MANUAL`이다(예약이 없는 STOCK과 기능 도입 전 실행 포함). education이 `ExitPlanRepository`를
  >   직접 주입하지 않도록 `PracticeExitPlanQueryService`를 거친다.
  > - **진입별 대조 배열은 041 6번으로 넘겼다.** 같은 배열에 041이 `unrealizedPnlIfHeld`("안 팔았다면" 평가
  >   손익)와 `priceAfterSell`을 얹도록 되어 있어, 042가 혼자 모양을 정하면 041이 그 모양에 묶이거나 다시
  >   고쳐야 한다. **새 이슈를 만들지 않았다** — 041 tasks 6번이 이미 이 배열을 명시하고 있어 같은 일이 두
  >   문서에 적히면 한쪽만 고쳐도 초록이 남는다(042 1번에서 도달 부등식으로 겪은 것과 같은 문제다).
  > - **넘긴 대가를 기록한다.** 그때까지 재진입한 사용자의 완료 화면은 첫 매도만 가리킨다 —
  >   `sellTradeId`·매도 시각·`sellCause`가 `firstSellTrade` 기준이기 때문이다. **금액은 맞다**
  >   (`realizedPnl`은 그 실행의 모든 매도 합, 매도 단가는 수량 가중평균). 틀리는 것은 "무슨 일이
  >   있었는가"이며, 2막 손절 → 3막 익절이 화면에서 손절 하나로 보인다.

> **API 문서는 각 항목이 자기 커밋에서 갱신한다**(CLAUDE.md 규칙 7). 3번은 새 엔드포인트를 만들므로
> `docs/api-routes.md`·`docs/api-contracts.md`를 그 커밋에서, 5·6·7번은 바꾼 응답 계약을 각자의 커밋에서
> 갱신한다. 문서 갱신을 마지막 항목으로 미루면 규칙 위반을 계획에 담는 것이 된다.

- [x] **8. 재진입 재예약 통합 테스트** — 손절 체결 → 재진입 대기 → **프리셋 변경** → 재매수 → 새 snapshot
  (`entry_sequence = 2`)과 새 예약이 바뀐 프리셋으로 생성됨. `SNAP-2` 배포 이후에만 통과한다.
  > `PracticeExitPresetOcoIntegrationTest.stopLossFillKeepsTheRunLedgerConsistentAndLetsTheUserReenterAndRestart`가
  > 이 시나리오를 그대로 실행한다. PR #487 리뷰 권장 1을 반영해 **새 예약의 손절·익절가가 RELAXED(5/8)를
  > 실제로 반영하는지**까지 단언한다 — 개수만 세면 프리셋이 BALANCED로 굳어 있어도 통과했다.

## 사람이 직접 확인할 것

- [ ] 배포본에서 CRYPTO 튜토리얼을 완주해 **손절 자동 체결과 익절 자동 체결을 한 실행 안에서** 본다
  (재매수 포함). 익절이 터지는 순간 손절 예약이 사라지는 것도 화면에서 확인한다.
- [ ] `CAUTIOUS`로 한 번, `BALANCED`로 한 번 완주해 **루머 단계에서 결과가 갈리는지** 확인한다.
  갈리지 않으면 대본 배율이나 프리셋 수치가 어긋난 것이다(041 tasks 1번 테스트가 먼저 잡아야 한다).

## 선행 확인 (구현 착수 전)

- [ ] 이슈 [#401](https://github.com/finplay-team/finplay-backend/issues/401)을 #444 중복으로 닫는다.
- [ ] `026`의 "참조선은 자동 청산을 유발하지 않는다" 문장을 개정할지 팀 판단 (plan 열린 질문)
- [ ] 프론트와 금액 병기 문구 형식 합의 — 계산은 클라이언트가 하고 서버는 비율·기준 가격만 준다
