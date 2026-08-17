# Run Log: 043-tutorial-order-query

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델 절, ADR-0002 도메인 경계 |
| - | implementer | `.\gradlew.bat compileJava` | plan.md education 도메인 절(`PracticeAttemptOrderQueryService`·`PracticeAttemptOrderController`), CLAUDE.md 규칙 7(api-routes·api-contracts 동기화) |
| - | implementer | `.\gradlew.bat test --tests PracticeAttemptOrderQueryIntegrationTest` (+ 기존 `TutorialSandboxPracticeIntegrationTest`·`LimitOrderPendingListIntegrationTest`·`OrderListIntegrationTest`·`PracticeAttemptRestartIntegrationTest` 재실행) | plan.md 테스트 계획 통합 시나리오, spec.md 완료 조건, 033 SANDBOX-EXCL 회귀 확인 |

## 모니터링 (사람용 요약)
- order 도메인에 `OrderRepository.findPracticeRunOrders`·`OrderService.getPracticeRunOrders` 추가, 컴파일 통과.
- education 도메인에 `PracticeAttemptOrderQueryService`·`PracticeAttemptOrderController`(`GET .../attempts/{market}/orders`) 추가, api-routes.md·api-contracts.md 동기화, 컴파일 통과.
- `PracticeAttemptOrderQueryIntegrationTest`(Testcontainers) 신규: attempt 없음→빈 목록, 지정가 매수→PENDING→tick 체결→FILLED→재시작 후 이전 run 제외를 한 시나리오로 검증(2건 모두 통과). 기존 샌드박스 제외·주문 목록 통합 테스트 4개 클래스(23건) 재실행해 회귀 없음 확인.
