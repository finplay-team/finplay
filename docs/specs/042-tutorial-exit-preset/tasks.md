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
- [ ] **SNAP-2** — 마이그레이션: 기존 `uk_practice_risk_snapshots_attempt_run` 삭제.
  **별도 PR·별도 배포다.** 새 UNIQUE가 같은 보호를 하므로(코드가 항상 `entry_sequence = 1`을 쓴다)
  이 창에서 중복 snapshot이 생길 수 없다.

## 작업 항목

- [ ] **1. 프리셋 상수와 계산** — `ExitPreset` enum(`CAUTIOUS` 2/3, `BALANCED` 3/5, `RELAXED` 5/8,
  기본값 `BALANCED`) + `ReferencePriceCalculator.calculateFromPercent` 연결.
  **테스트**: `BALANCED`로 계산한 `stopLossPrice`·`takeProfitPrice`가 현행 `entryPrice × 0.97`·`× 1.05`와
  **정확히 같은 값**임(EXITPRESET-002). 041 대본을 읽어 세 프리셋의 도달 부등식 판정(041 tasks 1번과
  같은 대상을 반대편에서 검사한다).

- [ ] **2. 스키마와 엔티티** — 마이그레이션: `practice_attempts.exit_preset`,
  `practice_risk_snapshots.exit_preset`, `exit_plans.practice_attempt_id`·`practice_attempt_run_number`
  (+ `orders`와 같은 모양의 CHECK: 둘 다 null이거나 둘 다 non-null, run > 0). 대응 엔티티 필드.

- [ ] **3. 선택 API와 잠금** — `PUT /api/education/practice/attempts/{market}/exit-preset`.
  **잠금 기준은 "현재 실행 세대의 순보유수량 == 0"이다** — 매수 전과 재진입 대기 중에는 허용, 보유 중에는
  409 `PRACTICE_STEP_LOCKED`. `PracticeAttemptResponse`에 `selectedExitPreset`·`exitPresetLocked`·
  `availableExitPresets` 추가.
  **테스트**: `@WebMvcTest` + 단위 — 보유 중 거부, **매도 후 다시 허용**, 정의 밖 값 400, 완료 후 409.

- [ ] **4. 매수 체결에 프리셋 반영 (예약 없이) + 진입당 1회 가드** — `createFirstBuyRiskSnapshot`을
  `createRiskSnapshotOnBuyFill`로 바꾸고 attempt의 프리셋과 `entry_sequence`(기존 수 + 1)를 반영.
  **직전 순보유수량이 0일 때만 snapshot을 만든다**(EXITPRESET-020) — 보유 중 추가 매수는 체결만 되고
  기준선이 움직이지 않는다. 여기까지는 "기존 동작 + 값이 달라짐"이라 회귀만 보면 된다. 예약은 5번에서 붙인다.
  **테스트**: 미선택 사용자의 결과가 이 기능 도입 전과 동일함, `exit_preset`이 null인 기존 snapshot이
  `BALANCED`로 해석됨.

- [ ] **5. CRYPTO 자동 예약 생성** — `ExitPlanCreateCommandDto.practice(...)` 팩토리 추가(attempt·run
  귀속), `market == CRYPTO`일 때만 같은 트랜잭션에서 `ExitPlanCreationService.create` 호출.
  `exitPriceType = PERCENT`로 비율을 함께 저장. **baseline을 041의 대본 canonical price로 주입한다** —
  엔진 기본 경로는 사인파 항시 시세를 읽는다. **STOCK은 snapshot까지만**(EXITPRESET-018).
  귀속 컬럼·baseline 주입 때문에 `ExitPlan` 생성 팩토리와 `newExitPlan`도 함께 바뀐다.
  **테스트**: 통합 — snapshot과 예약이 같은 트랜잭션에서 생기고 **실패 시 둘 다 남지 않음**.

- [ ] **6. tick 정산·재시작 정리·수동 매도 공존** — 세 가지를 한 덩어리로 본다. 전부 예약 원장이 얽힌다.
  - `PracticeOrderSettlementService.settleCurrentRun`에 `exitPlanFillService.fillIfPending(id, price)`
    루프 추가 + `ExitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)` 신규.
    순서는 지정가 → OCO로 고정.
  - `PracticeRunRestartOrderService`에 예약 취소를 **주문 취소보다 먼저** 넣는다(예약 수량이 남아 있으면
    보상 매도가 `availableQuantity` 부족으로 실패한다). `exit_preset`도 null로 초기화.
  - 튜토리얼 샘플 매도 주문 접수 전에 현재 run의 PENDING 예약을 같은 트랜잭션에서 전부 취소.
  **테스트**: 통합 — 대본이 손절선을 지날 때 체결되고 익절 예약이 자동 취소됨, 같은 가상 분 중복 tick이
  중복 체결을 안 만듦, 전량 예약 상태에서 시장가 매도가 정상 체결됨, 재시작이 예약 수량을 정확히 1회
  반환하고 다른 실행 세대·일반 경로 예약을 건드리지 않음.
  **정산·취소 뒤에는 `Holding`을 반드시 재조회한다** — 두 서비스가 `detach`를 호출하므로 같은 트랜잭션의
  기존 인스턴스를 재사용하면 수량 변경이 조용히 유실된다(plan 잔여 위험).

- [ ] **7. 매도 원인과 완료 대조** — `PracticeTradeResultResponse`에 `sellCause`
  (`STOP_LOSS`\|`TAKE_PROFIT`\|`MANUAL`) 추가하고, **진입별 대조를 배열로 제공**한다(041 SCENARIO-019b).
  현재 `PracticeAttemptEvidenceService`가 `tradeSummary.firstSellTrade()` 하나만 쓰므로, 재진입하면 완료
  화면에 2막 손절만 뜨고 3막 익절이 사라진다. `exit_plans.triggered_order_id` 역참조로 판정한다.
  나머지 대조 값(`sellPrice`·`realizedPnl`·`returnRate`·`sellVerdict`)은 이슈 #421로 이미 있다. 이 배열이 041의 "안 팔았다면" 선 재료도 겸한다.
  `docs/prd.md` §3에 `EXITPRESET-001~020` 행 추가.

> **API 문서는 각 항목이 자기 커밋에서 갱신한다**(CLAUDE.md 규칙 7). 3번은 새 엔드포인트를 만들므로
> `docs/api-routes.md`·`docs/api-contracts.md`를 그 커밋에서, 5·6·7번은 바꾼 응답 계약을 각자의 커밋에서
> 갱신한다. 문서 갱신을 마지막 항목으로 미루면 규칙 위반을 계획에 담는 것이 된다.

- [ ] **8. 재진입 재예약 통합 테스트** — 손절 체결 → 재진입 대기 → **프리셋 변경** → 재매수 → 새 snapshot
  (`entry_sequence = 2`)과 새 예약이 바뀐 프리셋으로 생성됨. `SNAP-2` 배포 이후에만 통과한다.

## 사람이 직접 확인할 것

- [ ] 배포본에서 CRYPTO 튜토리얼을 완주해 **손절 자동 체결과 익절 자동 체결을 한 실행 안에서** 본다
  (재매수 포함). 익절이 터지는 순간 손절 예약이 사라지는 것도 화면에서 확인한다.
- [ ] `CAUTIOUS`로 한 번, `BALANCED`로 한 번 완주해 **루머 단계에서 결과가 갈리는지** 확인한다.
  갈리지 않으면 대본 배율이나 프리셋 수치가 어긋난 것이다(041 tasks 1번 테스트가 먼저 잡아야 한다).

## 선행 확인 (구현 착수 전)

- [ ] 이슈 [#401](https://github.com/finplay-team/finplay-backend/issues/401)을 #444 중복으로 닫는다.
- [ ] `026`의 "참조선은 자동 청산을 유발하지 않는다" 문장을 개정할지 팀 판단 (plan 열린 질문)
- [ ] 프론트와 금액 병기 문구 형식 합의 — 계산은 클라이언트가 하고 서버는 비율·기준 가격만 준다
