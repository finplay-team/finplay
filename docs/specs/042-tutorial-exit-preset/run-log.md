# Run Log: 042-tutorial-exit-preset

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-19 19:45 | 메인 세션(직접 구현) | `./gradlew compileJava`, `./gradlew test --tests "*ExitPreset*" --tests "*ReferencePriceCalculator*" --tests "*PracticeAttempt*" --tests "*ExitPlan*"` | 042 tasks 1·2번, 041 tasks §교차 순서 4번 |
| 2026-08-19 19:53 | reviewer(리뷰) | `git diff origin/dev...HEAD` | 042 spec·plan·tasks, 041 plan §프리셋 도달 조건 검증, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:10 | reviewer ×3(리뷰) | `git diff origin/dev...HEAD` — 반영 재검증 / 결함 사냥 / 다음 세션 관점 | 042·041 spec·plan·tasks, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:40 | 메인 세션 | `./gradlew build` | PR 전 전체 검증 (git-conventions §머지 조건) |

## 모니터링 (사람용 요약)
- 19:45 — 042 1·2번 구현(이슈 #470). 프리셋 상수·계산 / V51 마이그레이션·엔티티 두 커밋.
- 19:53 — 리뷰: 차단 0건, 권장 3건, 참고 7건. 머지 가능.
- 20:05 — 권장 3건 + 손댈 수 있는 참고 3건 반영(왕복 테스트 `@EnumSource`, FK 자동 인덱스 부재 단언, 체결가 scale 8 선정규화, 현행 상수 직접 참조, 테스트 이름 정정, 탐색 루프 범위 방어).
- 20:10 — 2차 리뷰 3종: 차단 0건. V51 주석의 "컬럼을 읽지도 쓰지도 않는다"와 orders 인덱스 근거가 틀린 것을 잡아 실측으로 정정했고, 042 5번이 밟을 정규화 비대칭 함정을 tasks 5번·javadoc에 박았다.
- 20:15 — 문서와 다르게 간 판단(부등식 판정 위치, CHECK·인덱스 추가, 표시 이름 미구현)은 `tasks.md` 각 항목의 인용구와 V51 주석에 적었다 — 이 파일은 한 줄 요약만 둔다(`specs/README.md`).
- 20:40 — `./gradlew build` **BUILD SUCCESSFUL** (4,609건, 실패·오류 0건). 검증 SHA `73bdc9d9`. PR #471.

## 042 3~7번 판정 기록 (이슈 #477, 2026-08-20)

### 순보유수량 판정을 실행 세대 범위로 바로잡았다 — 가장 중요한 정정

plan §자동 예약 생성은 "직전 순보유수량 = 현재 순보유수량 − 이번 체결 수량"이라고만 적었고, 구현 초안은
그 "현재 순보유수량"을 `holdings` 행의 수량으로 읽었다. **틀렸다.** 그 값은 실행 세대를 넘어 누적되므로,
재시작이 청산하지 못한 이전 보유(attempt 도입 전에 만들어진 holding 등)가 남아 있으면 **새 실행의 첫
매수인데도 "이미 들고 있다"로 판정돼 위험 snapshot이 아예 만들어지지 않는다.**
`PracticeAttemptRestartRecompletionIntegrationTest`가 재현했다 — 단위 테스트는 전부 초록이었다.

`TradeService.netFilledQuantity(attemptId, runNumber)`(스칼라 집계 한 줄)로 바꿨고, **042의 프리셋 잠금·
진입당 1회 가드와 041의 대기 구간 탈출 판정이 이 한 메서드를 공유한다** — plan이 "구현에서 한 메서드로
모은다"고 요구한 자리다. 041이 만든 `HoldingService.findNetQuantity`는 이 변경이 만든 고아라 함께 지웠다.

### 순환 참조 — 매도 전 예약 취소를 포트에 두면 안 된다

"튜토리얼 매도 접수 전 PENDING 예약 취소"를 `PracticeOrderAttributionPort`에 두었더니
`PracticeOrderSettlementService` → `LimitOrderFillService` → 포트 → 정산 서비스로 순환 참조가 되어
**Spring 컨텍스트가 아예 뜨지 않았다.** 호출부(`OrderExecutionService`)가 `lockForOrder`로 이미 받은
귀속 정보(attemptId·runNumber)를 그대로 쓰도록 바꿔 포트를 넓히지 않았다.

### 귀속 컬럼의 애플리케이션 레벨 검증 — 두기로 했다

plan이 "5번에서 판정하라"고 남긴 항목이다. `ExitPlan.createPractice`가 두 귀속 값(attempt id·양의 실행
세대 번호)을 검증한다 — `Order.createPracticeFilled`의 `validatePracticeAttemptAttribution`과 대칭이다.
두지 않으면 이 불변식을 지키는 것이 DB CHECK 하나뿐이고, 위반이 트랜잭션 커밋 시점에야 드러난다.

### 진입별 대조 배열은 041 6번으로 넘겼다

같은 배열에 041이 `unrealizedPnlIfHeld`·`priceAfterSell`을 얹도록 되어 있어 042가 혼자 모양을 정하면
두 번 고쳐야 한다. **새 이슈를 만들지 않았다** — 041 tasks 6번이 이미 그 배열을 명시하고 있어, 같은 일을
두 문서에 적으면 한쪽만 고쳐도 초록이 남는다(042 1번의 도달 부등식에서 겪은 것과 같은 문제다).

넘긴 대가: 그때까지 재진입한 사용자의 완료 화면은 첫 매도만 가리킨다. **금액은 맞다** — 틀리는 것은
"무슨 일이 있었는가"이며 2막 손절 → 3막 익절이 손절 하나로 보인다.

### 응답 계약에서 정한 것

- **`exitPresetLocked`에는 기본값을 두지 않았다.** 잠금 여부는 순보유수량을 조회해야 알 수 있고, 잘못
  false로 내리면 클라이언트가 바꿀 수 없는 컨트롤을 열어 준다. 튜토리얼 계좌 잔고 3필드가 "이 호출부는
  모른다"로 0을 내리는 것과 성격이 다르다 — 컴파일러가 호출 지점 8곳에서 판단을 강제하게 했다.
- **`selectedExitPreset`은 미선택이어도 `null`이 아니라 `BALANCED`다**(EXITPRESET-002). 클라이언트가 null
  분기를 갖지 않고, 화면에 보이는 값과 실제로 적용될 값이 같아야 한다.
- **프리셋 표시 이름은 여전히 서버가 주지 않는다.** 042 1번이 남긴 "3번에서 정한다"에 대한 답이며,
  `availableExitPresets`가 식별자·비율만 내려보내고 문구는 클라이언트가 갖는다.
