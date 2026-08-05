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

## 모니터링 (사람용 요약)
- 10:39~10:46 implementer — V22 마이그레이션·OrderType.LIMIT/OrderStatus.PENDING·Order/Account/Holding 예약 메서드·4개 리포지토리 락 쿼리 추가, compileJava·compileTestJava·spotlessCheck 통과. Docker 미가용 환경이라 @DataJpaTest는 미실행(컴파일만 확인).
- 10:49~10:51 tester — Docker 데몬 기동 후 @DataJpaTest 포함 7개 테스트 클래스 63건 전체 통과(0 failure/0 error), 이어서 `./gradlew build` 전체(spotless·SpotBugs·JaCoCo·회귀)도 통과. 보완 테스트 없음 — implementer 작성분이 완료조건을 충분히 충족.
- 10:52 HEAD `e214739`(+선행 `b1a7b55` docs 커밋) — 항목1을 `docs: 015-limit-order spec 신설`과 `feat: ...` 두 커밋으로 분리해 커밋(오케스트레이터가 spec 문서·코드 분리 관례에 맞춰 히스토리 재구성, 내용 변경 없음).
- 10:55~11:02 implementer — `POST /api/orders/limit` 신설(LimitOrderCreateRequest/Response·LimitOrderCreationService·LimitOrderService·OrderController 추가), AccountService.getAccountForUpdate·PortfolioSellService.getHoldingForUpdateOrThrow 신규(ADR-0002 준수용 서비스 래퍼). 이 환경에 Docker가 없어 @DataJpaTest·@SpringBootTest 통합 테스트는 실행하지 않고 단위·@WebMvcTest만 실행, 전부 통과. docs/api-routes.md·api-contracts.md 갱신. docs/prd.md §3은 LMT-002까지 완료된 뒤(항목7) 갱신 예정이라 미변경.
- 11:04~11:07 tester — Docker 가용 확인 후 3개 테스트 클래스(49건) 전체 통과, IDEMPOTENCY_CONFLICT→409 WebMvc 매핑 테스트 1건 보완. `@SpringBootTest` 신규 통합 테스트는 이번 항목에 추가하지 않기로 판단(생성→체결 전체 흐름·동시성은 항목6이 이미 계획한 범위, 중복 방지). order/account/portfolio 전체 재실행(Testcontainers 포함)으로 시장가 회귀 없음 확인.
- 11:10~11:13 implementer — `CryptoPriceUpdatedEvent`(market.event) 신설, `PriceStore.saveTick`에 `ApplicationEventPublisher` 주입해 과거 틱 무시 분기를 통과했을 때만 publish하도록 변경(MKT-003 분기 자체는 미수정). `PriceStore`가 `@RequiredArgsConstructor` 3필드가 되며 `new PriceStore(...)` 직접 호출 3곳(`PriceStoreTest`·`PriceStoreIntegrationTest`·`FakeBithumbFeedClientIntegrationTest`)을 함께 수정. `PriceStoreTest`에 publish/미publish 신규 단위 테스트 4건 추가해 통과 확인, compileJava·compileTestJava·spotlessCheck 통과.
- 11:16~11:18 tester — Docker 가용, `PriceStoreIntegrationTest`·`FakeBithumbFeedClientIntegrationTest`(3-argument 생성자 변경 후 첫 실행) 포함 전체 통과, MKT-003 과거 틱 무시 회귀 없음 확인. 다중 심볼 동시 갱신 시 이벤트 payload 분리 테스트 1건 보완. `com.finplay.api.market.*` 전체(42개 클래스, 400건 이상) 회귀 재확인, 전부 통과. `CryptoPriceUpdatedEvent(symbol,price,receivedAt)`가 항목4 리스너의 `findByMarketAndSymbol` 조회에 충분한 페이로드임을 코드 리뷰로 확인.
