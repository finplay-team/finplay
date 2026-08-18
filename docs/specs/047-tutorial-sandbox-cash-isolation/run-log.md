# Run Log: 047-tutorial-sandbox-cash-isolation

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 18:46 | implementer | `./gradlew test --tests TutorialAccountRepositoryTest` | plan.md 데이터 모델·마이그레이션 설계, ADR-0004 |
| 19:20 | implementer | `./gradlew compileJava` | plan.md TutorialAccountService API 표, 설계 판단 — 계좌 생성 시점 |
| 20:05 | implementer | `./gradlew compileJava` | plan.md "호출부 변경 지점" 1·3·4번, spec TUTORIAL-CASH-ISOL-002·005 |

## 모니터링 (사람용 요약)
- 18:46 — TutorialAccount 엔티티·Repository·V46 마이그레이션·TUTORIAL_INSUFFICIENT_CASH 추가, DataJpaTest 6건 통과.
- 19:20 — TutorialAccountService(get-or-create·reset) 추가, ensureAttempt에 연결. compileJava 통과, 기존 PracticeAttemptServiceTest는 생성자 인자 불일치로 compileTestJava 깨짐(tester 몫).
- 20:05 — 매수 경로 전환(OrderExecutionService.createBuyOrder, PracticeLimitOrderCreationService.createSessionBuyOrder, LimitOrderFillService.fillBuy)을 샌드박스면 튜토리얼 계좌로 리다이렉트하도록 수정, TUTORIAL_INSUFFICIENT_CASH 분기 추가. compileJava 통과, 생성자 인자 추가로 OrderExecutionServiceTest·PracticeLimitOrderCreationServiceTest·LimitOrderFillServiceTest의 compileTestJava가 깨짐(tester 몫).
- 20:35 — 오케스트레이터 지시로 LimitOrderCreationService(일반 코인 지정가 생성, POST /api/orders/limit)도 범위에 포함. 조사 결과 "샌드박스 종목 생성 자체를 거부"는 오판임을 확인(spec.md TUTORIAL-CASH-ISOL-002가 이 경로를 명시적으로 포함하고, PracticeAttemptOrderAttributionService.lockForOrder는 isTutorialSample()이 아니면 항상 empty를 반환하므로 이 경로의 attempt 귀속 매수는 전부 샌드박스 종목 — 이미 통과 중인 기존 테스트 2건도 이를 검증함) — 대신 다른 3개 파일과 동일한 "튜토리얼 계좌로 리다이렉트" 방식으로 구현하고 근거를 보고에 남김. LimitOrderCreationServiceTest에 TUTORIAL_INSUFFICIENT_CASH·튜토리얼 계좌 예약 검증 테스트 2건 추가, 기존 tutorialSample 테스트 갱신. compileJava·LimitOrderCreationServiceTest 포함 compileTestJava 통과(기존 3개 파일의 compileTestJava 실패는 그대로 tester 몫). LimitOrderModifyService·LimitOrderCancelService에 동일 카테고리 미반영 갭 발견 — 후속 이슈로 분리(spawn_task).
- 21:05 — 오케스트레이터 지시로 LimitOrderModifyService.modifyOrder·LimitOrderCancelService.cancelOrder에도 동일 리다이렉트 패턴(isTutorialSample()이면 TutorialAccountService로 조회한 튜토리얼 계좌에서 releaseReservedCash/reserveCash) 적용. 두 서비스 모두 Clock 의존성이 없어 새로 추가(LocalDateTime.now(clock)로 튜토리얼 계좌 조회 시각 확보). 기존 LimitOrderModifyServiceTest·LimitOrderCancelServiceTest는 생성자 인자(TutorialAccountService, Clock) 추가로 컴파일이 깨져 최소 mock 추가로 직접 복구(신규 로직 검증 테스트는 미작성, tester 몫). compileJava 통과, compileTestJava는 이전과 동일하게 3개 파일(OrderExecutionServiceTest·PracticeLimitOrderCreationServiceTest·LimitOrderFillServiceTest)만 실패.
- 21:25 | implementer | `./gradlew test --tests com.finplay.api.order.service.OrderExecutionServiceTest` | tester가 찾은 회귀 리포트(호출 순서 계약 위반) — tester가 이미 OrderExecutionServiceTest 생성자를 TutorialAccountService 인자로 갱신해 컴파일은 통과하던 상태였고, createBuyOrder에서 `userQueryService.getUser(userId)` 호출을 현금 검증(튜토리얼/실제 분기 전체) 이후·`createOrder` 호출 직전으로 이동해 "실패 시 무흔적" 순서를 복원. `./gradlew test --tests OrderExecutionServiceTest` 33건 전부 통과(failures=0, errors=0) — 회귀로 지목된 createOrderThrowsInsufficientCashWhenCashBalanceBelowAmountPlusFee·createOrderThrowsInsufficientCashWhenAvailableCashBelowAmountPlusFeeEvenIfCashBalanceSuffices 포함.
