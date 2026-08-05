# Run Log: 015-limit-order

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 10:39 | implementer(항목1) | `./gradlew compileJava` | plan.md SQL·엔티티·리포지토리 설계, ADR-0004(Flyway) |
| 10:42 | implementer(항목1) | `./gradlew compileTestJava` | docs/conventions.md 테스트 작성 규칙 |
| 10:44 | implementer(항목1) | `./gradlew spotlessApply && spotlessCheck` | docs/conventions.md 포맷 규칙 |
| 10:46 | implementer(항목1) | `./gradlew test --tests OrderTest --tests AccountTest --tests HoldingTest` | plan.md 엔티티 불변식 단위 테스트 (Docker 미가용 환경, @DataJpaTest는 컴파일만 확인) |
| 10:49 | tester(항목1) | `./gradlew test --tests OrderTest --tests AccountTest --tests HoldingTest --tests AccountRepositoryTest --tests HoldingRepositoryTest --tests OrderRepositoryTest --tests InstrumentRepositoryTest` (Docker 데몬 기동 후 Testcontainers MySQL로 실행) | tasks.md 항목1 완료조건, ADR-0003(Repository 쿼리는 @DataJpaTest) |
| 10:51 | tester(항목1) | `./gradlew build` | 전체 게이트(spotless·SpotBugs·JaCoCo·회귀) 확인 |
| 10:55 | implementer(항목2) | `./gradlew compileJava compileTestJava` | plan.md "지정가 생성 흐름", ADR-0002(cross-domain repo 직접 주입 금지) |
| 10:57 | implementer(항목2) | `./gradlew spotlessApply && spotlessCheck` | docs/conventions.md 포맷 규칙 |
| 11:00 | implementer(항목2) | `./gradlew test --tests LimitOrderCreationServiceTest --tests LimitOrderServiceTest --tests OrderControllerTest` | 서비스 단위·@WebMvcTest, docs/conventions.md 테스트 작성 규칙 |
| 11:02 | implementer(항목2) | `./gradlew test --tests "order.*" --tests "account.service.*" --tests "account.domain.*" --tests "portfolio.service.*" --tests "portfolio.domain.*"` | 기존 시장가 매수·매도 회귀 확인 |
| 11:04 | tester(항목2) | `docker info` (데몬 즉시 가용) | Docker 기반 통합 테스트 준비 |
| 11:05 | tester(항목2) | `./gradlew test --tests LimitOrderCreationServiceTest --tests LimitOrderServiceTest --tests OrderControllerTest` | tasks.md 항목2 완료조건 커버리지 점검 |
| 11:06 | tester(항목2) | `OrderControllerTest`에 IDEMPOTENCY_CONFLICT→409 매핑 테스트 1건 보완 | 완료조건 "Idempotency-Key 필수+재조회/409 IDEMPOTENCY_CONFLICT" — WebMvc 계층 매핑 검증 누락 발견 |
| 11:07 | tester(항목2) | `./gradlew test --tests "order.*" --tests "account.*" --tests "portfolio.*"` (Testcontainers 포함 전체) | 시장가 매수·매도 회귀 재확인, ADR-0003 |
| 11:10 | implementer(항목3) | `./gradlew compileJava compileTestJava` | plan.md "가격 갱신 이벤트(LMT-002 트리거)" 절, spec.md 확정된 설계 결정 3번 |
| 11:12 | implementer(항목3) | `./gradlew spotlessApply && spotlessCheck` | docs/conventions.md 포맷 규칙 |
| 11:13 | implementer(항목3) | `./gradlew test --tests PriceStoreTest` | 신규 틱 publish·과거/동시각 틱 미publish 단위 테스트(순수 mock, Docker 불필요). MKT-003 회귀는 `PriceStoreIntegrationTest`·`FakeBithumbFeedClientIntegrationTest` 생성자 호출부 수정으로 컴파일까지만 확인, 실행은 tester 담당 지시에 따라 미실행 |
| 11:16 | tester(항목3) | `./gradlew test --tests PriceStoreTest --tests PriceStoreIntegrationTest --tests FakeBithumbFeedClientIntegrationTest` (Testcontainers 포함, 이번이 두 통합테스트 첫 실행) | tasks.md 항목3 완료조건, MKT-003 회귀 확인 |
| 11:17 | tester(항목3) | `PriceStoreTest`에 다중 심볼 동시 갱신 시 이벤트별 payload 분리 테스트 1건 보완 | 여러 심볼 동시 갱신 시 이벤트가 심볼별로 올바르게 발행되는지 커버리지 공백 발견 |
| 11:18 | tester(항목3) | `./gradlew test --tests "market.*"` (42개 클래스, Testcontainers 포함) | MKT-003 등 기존 시세 기능 전체 회귀 확인 |
| 11:20 | implementer(항목4) | plan.md "체결 리스너·체결 서비스", "잠금 순서 요약" 절 재확인, `OrderRepository.findPendingLimitOrdersToFill` 정렬(requestedAt asc, id asc) 항목1 구현 확인 | 잠금 순서·정렬 요구사항 검증 |
| 11:26 | implementer(항목4) | `./gradlew compileJava` | LimitOrderFillService·LimitOrderTriggerListener·AccountService.getAccountByIdForUpdate·InstrumentService.findEntityByMarketAndSymbol·PortfolioSellService 신규 메서드 2개 |
| 11:27 | implementer(항목4) | `./gradlew compileJava compileTestJava` (신규 테스트 3개 클래스 추가 후) | 서비스 단위 테스트 작성 규칙 |
| 11:27 | implementer(항목4) | `./gradlew spotlessApply && compileJava compileTestJava spotlessCheck` | docs/conventions.md 포맷 규칙 |
| 11:28 | implementer(항목4) | `./gradlew test --tests LimitOrderFillServiceTest --tests LimitOrderTriggerListenerTest --tests PortfolioSellServiceTest --tests OrderExecutionServiceTest` | BUY/SELL 체결·no-op·신규종목 첫매수 단위 테스트 + 기존 시장가 매도 회귀(OrderExecutionService 미변경 확인) |
| 11:35 | tester(항목4) | `./gradlew test --tests LimitOrderFillServiceTest --tests LimitOrderTriggerListenerTest --tests PortfolioSellServiceTest --tests OrderExecutionServiceTest` | tasks.md 항목4 완료조건, `finalizeSellRealizedPnl` 회귀 확인 |
| 11:36 | tester(항목4) | `LimitOrderFillService.fillIfPending` 락 획득 순서를 spec.md 원문과 코드 대조 | order→account→holding 실제 순서 확인, plan.md 257~267행 근거와 일치 여부 판단 |
| 11:38 | tester(항목4) | `LimitOrderFillServiceTest`에 2회 연속 호출 시 부작용 미중복 테스트 1건 보완 | no-op·중복 부작용 방지 완료조건 커버리지 공백 발견 |
| 11:40 | tester(항목4) | `LimitOrderFillIntegrationTest` 신규 작성(`@SpringBootTest`+Testcontainers, `RankingIntegrationTest` 패턴) | PriceStore.saveTick→이벤트→리스너→체결까지 실제 Spring 배선이 검증되지 않는 공백 발견(항목6과 검증 대상 안 겹침) |
| 11:43 | tester(항목4) | `./gradlew test --tests "order.*" --tests "account.*" --tests "portfolio.*" --tests "market.*"` (87개 클래스, Testcontainers 포함) | 전체 회귀 재확인 |
| 11:45 | 오케스트레이터 | spec.md LMT-002 잠금 순서 문구를 `account→holding→order`에서 `order→account→holding`으로 정정(확정된 설계 결정 7번 신설), 사용자에게 질문해 "구현 유지+spec 문구 수정" 확정 | tester 검증 결과 실제 ABBA 위험 없음 확인, plan.md 설계 근거와 spec.md 원문 불일치 해소 |

## 모니터링 (사람용 요약)
- 10:39~10:46 implementer — V22 마이그레이션·OrderType.LIMIT/OrderStatus.PENDING·Order/Account/Holding 예약 메서드·4개 리포지토리 락 쿼리 추가, compileJava·compileTestJava·spotlessCheck 통과. Docker 미가용 환경이라 @DataJpaTest는 미실행(컴파일만 확인).
- 10:49~10:51 tester — Docker 데몬 기동 후 @DataJpaTest 포함 7개 테스트 클래스 63건 전체 통과(0 failure/0 error), 이어서 `./gradlew build` 전체(spotless·SpotBugs·JaCoCo·회귀)도 통과. 보완 테스트 없음 — implementer 작성분이 완료조건을 충분히 충족.
- 10:52 HEAD `e214739`(+선행 `b1a7b55` docs 커밋) — 항목1을 `docs: 015-limit-order spec 신설`과 `feat: ...` 두 커밋으로 분리해 커밋(오케스트레이터가 spec 문서·코드 분리 관례에 맞춰 히스토리 재구성, 내용 변경 없음).
- 10:55~11:02 implementer — `POST /api/orders/limit` 신설(LimitOrderCreateRequest/Response·LimitOrderCreationService·LimitOrderService·OrderController 추가), AccountService.getAccountForUpdate·PortfolioSellService.getHoldingForUpdateOrThrow 신규(ADR-0002 준수용 서비스 래퍼). 이 환경에 Docker가 없어 @DataJpaTest·@SpringBootTest 통합 테스트는 실행하지 않고 단위·@WebMvcTest만 실행, 전부 통과. docs/api-routes.md·api-contracts.md 갱신. docs/prd.md §3은 LMT-002까지 완료된 뒤(항목7) 갱신 예정이라 미변경.
- 11:04~11:07 tester — Docker 가용 확인 후 3개 테스트 클래스(49건) 전체 통과, IDEMPOTENCY_CONFLICT→409 WebMvc 매핑 테스트 1건 보완. `@SpringBootTest` 신규 통합 테스트는 이번 항목에 추가하지 않기로 판단(생성→체결 전체 흐름·동시성은 항목6이 이미 계획한 범위, 중복 방지). order/account/portfolio 전체 재실행(Testcontainers 포함)으로 시장가 회귀 없음 확인.
- 11:10~11:13 implementer — `CryptoPriceUpdatedEvent`(market.event) 신설, `PriceStore.saveTick`에 `ApplicationEventPublisher` 주입해 과거 틱 무시 분기를 통과했을 때만 publish하도록 변경(MKT-003 분기 자체는 미수정). `PriceStore`가 `@RequiredArgsConstructor` 3필드가 되며 `new PriceStore(...)` 직접 호출 3곳(`PriceStoreTest`·`PriceStoreIntegrationTest`·`FakeBithumbFeedClientIntegrationTest`)을 함께 수정. `PriceStoreTest`에 publish/미publish 신규 단위 테스트 4건 추가해 통과 확인, compileJava·compileTestJava·spotlessCheck 통과.
- 11:16~11:18 tester — Docker 가용, `PriceStoreIntegrationTest`·`FakeBithumbFeedClientIntegrationTest`(3-argument 생성자 변경 후 첫 실행) 포함 전체 통과, MKT-003 과거 틱 무시 회귀 없음 확인. 다중 심볼 동시 갱신 시 이벤트 payload 분리 테스트 1건 보완. `com.finplay.api.market.*` 전체(42개 클래스, 400건 이상) 회귀 재확인, 전부 통과. `CryptoPriceUpdatedEvent(symbol,price,receivedAt)`가 항목4 리스너의 `findByMarketAndSymbol` 조회에 충분한 페이로드임을 코드 리뷰로 확인.
- 11:20~11:28 implementer — `LimitOrderTriggerListener`(order.listener, `@EventListener` + 리스너 전체·건별 이중 try/catch, `RankingEventListener` 관례 재사용)·`LimitOrderFillService`(`@Transactional`, order→account→(SELL만)holding 락) 신설. `AccountService.getAccountByIdForUpdate`·`InstrumentService.findEntityByMarketAndSymbol`(ADR-0002 준수용 신규 래퍼), `PortfolioSellService.getHoldingForUpdate`(예약분 재검증 없이 락만)·`finalizeSellRealizedPnl`(실현손익 계산·반영 공통화) 추가. **`OrderExecutionService`는 손대지 않았다** — 처음엔 `finalizeSellRealizedPnl`을 `OrderExecutionService.createSellOrder`에서도 호출하도록 리팩터링했으나, `OrderExecutionServiceTest`가 `PortfolioSellService`를 mock으로 주입해 실현손익 반영을 인라인 로직으로 직접 검증하는 구조라 그대로 적용하면 mock이 실제 계산을 수행하지 않아 기존 테스트가 깨지는 것을 확인하고 되돌렸다(운영 동작은 동일하되 테스트 안전성을 우선). 지정가 체결 경로만 새 공통 메서드를 사용한다. 단위 테스트 4개 클래스(`LimitOrderFillServiceTest`·`LimitOrderTriggerListenerTest` 신규, `PortfolioSellServiceTest`·`OrderExecutionServiceTest` 확장/회귀 확인) 전부 통과, compileJava·compileTestJava·spotlessCheck 통과. 이번 항목은 아직 시장가 매도 락 순서 미조정(항목5) 상태라 ABBA 가능성이 남아있음을 인지하고 있으나 지정가 체결 자체의 락 순서(order→account→holding)만 정확히 구현했다.
- 11:35~11:43 tester — 기존 단위 테스트 전부 통과 확인 후, 실제 락 획득 순서가 `order→account→holding`으로 spec.md 원문(`account→holding→order`)과 문자 그대로는 다름을 발견 — plan.md가 이미 이 편차를 설계 단계에서 근거를 남겼고(order 락은 같은 주문에 대한 중복 이벤트만 배제, 실제 경합 자원인 account·holding은 항상 account 먼저) 코드 대조 결과 실제 ABBA 데드락 경로는 없음을 확인, 정책 판단이 필요해 오케스트레이터에게 보고. `finalizeSellRealizedPnl`과 기존 `OrderExecutionService` 실현손익 계산식을 항 단위로 대조해 완전히 동일함을 확인. 2회 연속 호출 부작용 미중복 테스트 1건, `LimitOrderFillIntegrationTest`(실제 이벤트 배선 end-to-end 검증) 1건 보완. 87개 클래스 전체 회귀 재확인, 전부 통과.
- 11:45 오케스트레이터 — 사용자 확인 후 spec.md LMT-002 잠금 순서 문구를 실제 구현(`order→account→holding`)에 맞춰 정정, "확정된 설계 결정" 7번 신설.
