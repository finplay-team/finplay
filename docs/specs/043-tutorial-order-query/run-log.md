# Run Log: 043-tutorial-order-query

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델 절, ADR-0002 도메인 경계 |
| - | implementer | `.\gradlew.bat compileJava` | plan.md education 도메인 절(`PracticeAttemptOrderQueryService`·`PracticeAttemptOrderController`), CLAUDE.md 규칙 7(api-routes·api-contracts 동기화) |

## 모니터링 (사람용 요약)
- order 도메인에 `OrderRepository.findPracticeRunOrders`·`OrderService.getPracticeRunOrders` 추가, 컴파일 통과.
- education 도메인에 `PracticeAttemptOrderQueryService`·`PracticeAttemptOrderController`(`GET .../attempts/{market}/orders`) 추가, api-routes.md·api-contracts.md 동기화, 컴파일 통과.
