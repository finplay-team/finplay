# Run Log: 042-tutorial-exit-preset

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-19 19:4x | 메인 세션(직접 구현) | `./gradlew compileJava`, `./gradlew test --tests "*ReferencePriceCalculatorTest*" --tests "*ExitPresetScenarioReachabilityTest*" --tests "*TutorialScenarioScriptIntegrityTest*" --tests "*PracticeAttemptTest*" --tests "*PracticeAttemptRepositoryTest*" --tests "*ExitPlanRepositoryTest*" --tests "*ExitPlanSchemaConstraintsTest*"` | 042 tasks 1·2번, 041 tasks §교차 순서 4번 |

## 모니터링 (사람용 요약)
- 19:4x — 042 1·2번 구현(이슈 #470). 프리셋 상수·계산 커밋과 마이그레이션·엔티티 커밋 둘. 파일 1~2개 규모는
  아니지만 spec·plan·tasks가 이미 확정돼 있어 `/feature`의 planner 단계가 필요 없다고 보고 직접 구현 경로로
  진행했으며, 빠지는 마무리 리뷰는 reviewer 서브에이전트를 PR 전에 따로 투입해 채웠다.

## 판단 기록 (문서와 다르게 하거나 문서가 정하지 않은 것)

### 도달 부등식 판정을 042 쪽 새 테스트로 옮겼다
041 tasks 1번이 `TutorialScenarioScriptIntegrityTest`에 부등식 전부를 프리셋 리터럴로 넣어 두고 "042의
프리셋 상수가 들어오면 그 상수를 읽도록 바꾼다"고 적어 뒀고, 042 tasks 1번은 "041 tasks 1번과 같은 대상을
반대편에서 검사한다"고 적었다. **같은 조건을 두 곳에서 검사하면 한쪽만 고쳐도 초록이 유지되므로 한 곳으로
모았다.**

- **프리셋이 걸린 것**(1막 익절 미발동, 루머 분기 비겹침, 속임수 반등, 확정 손절, 3막 익절·4막 손절, 익절 후
  추가 상승 상한, CAUTIOUS 손절 시점 ≥ 루머 공개 시점)은 새 테스트
  `education.marketpractice.domain.ExitPresetScenarioReachabilityTest`로 옮겼다. EXITPRESET-010의 판정
  주체가 프리셋이고, `market` 패키지 테스트가 `education`의 열거형을 참조하지 않게 된다.
- **대본 내부 성질**(구간 순서·길이·극값, 사건 배치, 무귀속 분 > 귀속 분, 문안 접두)만 041 테스트에 남겼다.
- 새 테스트는 **양쪽 모두 리터럴 없이 읽는다.** 루머 저점 `0.975`도 상수로 적지 않고 대본에서 읽는다 —
  적어 두면 대본이 바뀐 뒤에도 옛 값 기준으로 통과한다. 그 저점이 계획대로 0.975인지는 041의 극값 테스트가 본다.
- 기준선을 직접 곱하지 않고 `ReferencePriceCalculator.calculateFromPreset`으로 계산한다. 사용자가 실제로
  겪는 선이 그 계산기가 만든 선이므로, 부등식과 production 계산 경로가 갈라질 여지를 없앤다.

### plan에 없는 것을 더한 것 둘
- **`exit_preset` CHECK 제약.** plan §데이터 모델은 `VARCHAR(20) NULL`만 적었다. V38이 `market`·`status`를
  값 집합 CHECK로 막고 있어 같은 방식을 따랐다. 비용은 프리셋을 늘릴 때 CHECK를 함께 고쳐야 한다는 것인데,
  프리셋 수치는 041 대본과 맞물려 있어 어차피 단독으로 바꿀 수 없다.
- **`idx_exit_plans_practice_attempt_run_status`.** 042 tasks 6번이 요구하는
  `findPendingPracticeRunExitPlanIds(attemptId, runNumber)`가 쓸 인덱스를 컬럼과 같은 배포에 넣었다. 뒤로
  미루면 마이그레이션이 하나 더 필요하고 그 사이 조회가 전체 스캔이 된다. **FK보다 먼저** 만드는데, 선두
  컬럼이 `practice_attempt_id`라 FK가 이 인덱스를 그대로 쓰기 때문이다(순서를 뒤집으면 단일 컬럼 인덱스가
  하나 더 남는다). orders는 FK → 인덱스 순서라 이 한 가지가 다르다.

### plan에 있는데 만들지 않은 것
- **프리셋의 표시 이름**(조심스럽게·보통·느긋하게). plan §프리셋 수치 표에는 있지만 §API 계약의
  `availableExitPresets`는 식별자·손절률·익절률만 내려보낸다. 서버가 쓰지 않는 한국어 문구를 열거형에 두면
  클라이언트가 그것을 기대하게 되므로 넣지 않았다. 필요해지면 042 3번에서 응답 계약과 함께 정한다.

### 비율 단위
`ExitPreset`의 손절률·익절률은 **퍼센트 수**(3%는 `3`)다. `ReferencePriceCalculator.calculateFromPercent`가
내부에서 `rate.divide(100)`을 하고 `exit_plans.stop_loss_rate DECIMAL(7,4)`도 같은 단위이기 때문이다.
분수로 적으면 손절선이 진입가의 99.97%가 되는데 예외가 나지 않아 조용히 틀린다 — 단위 테스트로 못박았다.
