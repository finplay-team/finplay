# Tasks: 조회 — PORT-003 주문 목록 (GitHub 이슈 #21)

> 이 tasks.md는 `spec.md`의 5개 요구사항 중 PORT-003(주문 목록, 이슈 #21)만 다룬다. ACCT-002·ACCT-003·PORT-001·PORT-002는 별도 계획 대상이다(`plan.md` 상단 범위 안내 참고).
> 기존 `order` 도메인(이슈 #13, PR #88)에 이어 붙이는 작업이라 새 엔티티·마이그레이션·도메인 패키지가 필요 없다.

- [x] **Repository: 사용자별 최신순 주문 조회 쿼리 메서드**
  - `OrderRepository`에 `findAllByUserIdOrderByRequestedAtDescIdDesc(Long userId)` 추가 (`instrument` `JOIN FETCH` 포함 JPQL, plan.md 참고).
  - `@DataJpaTest` 슬라이스 테스트: 본인 주문만 반환, `requestedAt` 내림차순·동시각 `id` 내림차순 정렬, 주문 없는 사용자는 빈 목록.

- [x] **응답 DTO: `OrderListItemResponse`**
  - `order/dto/response/OrderListItemResponse.java` record 추가 — `orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`requestedAt` 8개 필드, 정적 팩토리 `from(Order order)`.
  - 체결 전용 필드(`tradeId`·`price`·`amount`·`fee`·`executedAt`)를 포함하지 않는지 코드 리뷰 관점에서 스스로 재확인(spec 요구사항).

- [x] **Service: `OrderService.getMyOrders`**
  - 기존 `OrderService`에 `@Transactional(readOnly = true) getMyOrders(Long userId)` 추가 — repository 결과를 `OrderListItemResponse.from(...)`으로 매핑.
  - 단위 테스트(`OrderServiceTest`, Mockito): 매핑 필드 정확성, 빈 목록 처리. 응답 객체를 mock으로 만들지 않고 실제 값으로 검증.

- [x] **Controller: `GET /api/orders`**
  - 기존 `OrderController`에 `@GetMapping` 메서드 추가 — `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻어 서비스 호출, 200 응답.
  - `@WebMvcTest` 슬라이스 테스트(`OrderControllerTest`): 200 성공 시 `jsonPath`로 8개 필드 값 검증(+ 체결 전용 필드 부재 확인), 인증 실패 401.

- [x] **통합 테스트: 매수 파이프라인 기반 시나리오**
  - Testcontainers 기반 기존 통합 테스트 파일(또는 인접 파일)에 시나리오 추가: 이슈 #13 매수 API로 실제 주문 데이터 생성(여러 건, 가능하면 타인 주문 포함) → `GET /api/orders` 호출 → 본인 범위·최신순·필드 계약·타인 주문 제외·체결 필드 미노출을 한 시나리오에서 검증.
  - 주문이 없는 신규 사용자에 대한 200 빈 배열 케이스 포함.

- [x] **문서 동기화: `docs/api-routes.md` · `docs/api-contracts.md`**
  - `api-routes.md` 라우트 표에 `GET /api/orders` 행 추가(Spec 컬럼에 `006 PORT-003, Issue #21` 표기).
  - `api-contracts.md`의 `## order` 절에 "내 주문 목록 조회" 표 추가 — 요청 없음, 성공 200 예시(`OrderListItemResponse[]`), 오류 401만.
  - 같은 커밋에서 두 문서를 함께 갱신(CLAUDE.md 규칙 7).

---

## 이슈 #47: 보유 평가 계산 공통 구현 (ACCT-002 · PORT-001 선행)

> 이 섹션은 `spec.md`의 ACCT-002·PORT-001이 요구하는 "평가금액·미실현손익 계산"의 공통 진입점만 다룬다(`plan.md` 이슈 #47 절 참고). API·controller 변경이 없어 문서 동기화 항목이 없다. 계좌 요약(#81)·보유 종목(#52)·합산 포트폴리오(#51)는 각자 착수될 때 이 서비스를 재사용해 별도 tasks로 진행한다.

- [x] **`HoldingValuationDto` + `HoldingValuationService.evaluateHolding` 구현 (정상 케이스)**
  - 신규 파일 `com.finplay.api.portfolio.service.HoldingValuationDto`(record: `quantity`·`averagePrice`·`costBasis`·`priceStatus`·`evaluationAmount`·`unrealizedPnl`·`returnRate`, plan.md 표 참고), `com.finplay.api.portfolio.service.HoldingValuationService`(`evaluateHolding(Holding holding)`, `PriceQueryService.getPriceQuote(Instrument)` 비throw 변형만 사용).
  - 반올림 규칙(plan.md "계산 규칙 확정" 절): `costBasis`·`evaluationAmount`는 `BigDecimal.setScale(0, RoundingMode.FLOOR).longValueExact()`, `unrealizedPnl`은 정수 뺄셈, `returnRate`는 `divide(..., 4, RoundingMode.HALF_UP)`.
  - 단위 테스트(`HoldingValuationServiceTest`, Mockito로 `PriceQueryService` stub): 이익 케이스·손실 케이스 각각에서 4개 계산 필드를 실제 수치로 검증(mock 응답 객체 금지 컨벤션).

- [x] **경계 케이스 처리 및 단위 테스트**
  - 시세 무효(`PriceStatus.UNAVAILABLE`) 케이스: 예외를 던지지 않고 `evaluationAmount`·`unrealizedPnl`·`returnRate`가 모두 `null`로 반환되는지 검증(plan.md "시세 무효 종목 처리 규칙 확정" 절).
  - `costBasis == 0`이 되는 두 경계(보유수량 0, 평균단가 0) 각각에서 `ArithmeticException` 없이 `returnRate = BigDecimal.ZERO`로 반환되는지 검증(plan.md "경계 케이스 처리표" 참고).

- [x] **`PriceQueryService` 연동 확인 및 회귀 검증**
  - `HoldingValuationService`가 throw 변형 `getPrice(...)`가 아니라 비throw 변형 `getPriceQuote(...)`만 호출하는지 코드 리뷰 관점에서 재확인 — "시세 무효가 조회 자체를 막지 않는다"는 요구사항의 직접 구현이므로 실수로 throw 변형을 쓰면 계약 위반.
  - 기존 `order`·`portfolio`·`market` 패키지 테스트 스위트에 회귀가 없는지 확인, `./gradlew build` 통과(SpotBugs·JaCoCo 게이트 포함).

---

## 이슈 #81: 시장별 계좌 요약 조회 API 구현 (ACCT-002, GitHub 이슈 #81)

> 이 섹션은 `spec.md`의 ACCT-002(실제 조회 API)만 다룬다. 평가 계산 자체는 이슈 #47(`HoldingValuationService`, 병합됨)을 그대로 재사용한다(`plan.md` 이슈 #81 절 참고).

- [x] **Repository: 계좌별 활성 보유 조회 + `HoldingValuationService` 계좌 단위 진입점**
  - `HoldingRepository`에 `findAllByAccountIdAndIsActiveTrue(Long accountId)` 추가 (`instrument` `JOIN FETCH` 포함 JPQL, plan.md 참고).
  - `HoldingValuationService`에 `HoldingRepository`를 새로 주입하고 `evaluateActiveHoldingsForAccount(Long accountId)` 추가(계좌의 활성 보유를 조회해 각각 `evaluateHolding`으로 평가한 `List<HoldingValuationDto>` 반환). **`account` 도메인이 `HoldingRepository`를 직접 참조하면 안 된다(ADR-0002) — 이 메서드가 유일한 진입점이어야 한다.**
  - `@DataJpaTest` 슬라이스 테스트(`HoldingRepositoryTest`, 신규): 다른 계좌·전량 매도(`isActive=false`) 보유 제외, `instrument` 지연 로딩 예외 없이 접근 가능.
  - 단위 테스트(`HoldingValuationServiceTest`, 기존 파일): `evaluateActiveHoldingsForAccount`가 활성 보유 목록을 정확히 평가·매핑하는지(시세 유효/무효 혼합 포함), 빈 목록 처리.

- [x] **응답 DTO: `AccountSummaryResponse`**
  - `account/dto/response/AccountSummaryResponse.java` record 추가 — `cashBalance`·`holdingsValue`·`totalValue`·`realizedPnl`·`unrealizedPnl`·`returnRate` 6개 필드, 정적 팩토리 `of(...)` (plan.md 표 참고).

- [x] **Service: `AccountService.getAccountSummary`**
  - 기존 `AccountService`에 `HoldingValuationService` 의존성 추가(생성자를 `@RequiredArgsConstructor`로 교체 — `HoldingRepository`는 주입하지 않음), `@Transactional(readOnly = true) getAccountSummary(Long userId, Market market)` 추가.
  - 기존 `getAccountFor(userId, market)`를 그대로 호출해 소유권 검증 재사용(별도 403/404 분기 없음).
  - 시세 유효(`AVAILABLE`) 보유만 `holdingsValue`·`unrealizedPnl` 합산에 반영, 무효(`UNAVAILABLE`) 보유는 합산에서 제외(0 기여, plan.md "시세 무효 종목 합산 정책 확정" 절).
  - `totalValue = cashBalance + holdingsValue`, `returnRate = (totalValue - seedMoney) / seedMoney`(scale 4, `RoundingMode.HALF_UP`, `seedMoney == 0`이면 `BigDecimal.ZERO`).
  - 단위 테스트(`AccountServiceTest`, 기존 파일, Mockito로 `HoldingValuationService` stub): 시세 유효만 있는 케이스·시세 무효 혼합 케이스(제외 확인)·활성 보유 없음(모두 0) 케이스·계좌 없음(`NOT_FOUND` 회귀) 각각 실제 수치로 검증.

- [x] **Controller: `GET /api/accounts/summary`**
  - 신규 패키지 `com.finplay.api.account.controller`에 `AccountController` 추가 — `@GetMapping("/summary")`, `@AuthenticationPrincipal AuthenticatedUser`, `@RequestParam com.finplay.api.account.domain.Market market`(필수 — `market.domain.Market`을 잘못 import하지 않도록 주의, plan.md "Market 타입 주의" 참고).
  - `market` 누락·잘못된 리터럴은 기존 `GlobalExceptionHandler`가 이미 400 `VALIDATION_ERROR`로 처리하므로 컨트롤러에 별도 검증 코드를 추가하지 않는다.
  - `@WebMvcTest` 슬라이스 테스트(`AccountControllerTest`, 신규, `OrderControllerTest` 패턴 재사용): `market=STOCK`·`market=CRYPTO` 200 필드 계약, `market` 누락 400, `market=FOREX` 400, 인증 실패 401.

- [x] **통합 테스트: 매수 파이프라인 기반 계좌 요약 시나리오**
  - Testcontainers 기반 통합 테스트(기존 매수 통합 테스트 파일 인접 또는 신규 `AccountSummaryIntegrationTest`)에 시나리오 추가: 회원가입 직후 빈 계좌 200(모두 0, `cashBalance`는 초기 시드머니) → 매수 API로 실제 매수 실행 후 재조회해 6개 값이 원장·시세 기준으로 정확히 일치 → 시세 무효 종목 보유 상황에서도 예외 없이 200(해당 종목 합산 제외 확인) → 타인 계좌 매수가 본인 조회에 섞이지 않음.

- [x] **문서 동기화: `docs/prd.md` · `docs/api-routes.md` · `docs/api-contracts.md`**
  - `docs/prd.md` ACCT-002 절에 수익률 필드를 추가(계산식 근거 명시, #51이 동일 계산식을 재사용함을 명시).
  - `docs/api-routes.md` 라우트 표에 `GET /api/accounts/summary?market=` 행 추가(Spec 컬럼에 `006 ACCT-002, Issue #81` 표기).
  - `docs/api-contracts.md`에 신규 `## account` 절 추가 — 요청(쿼리 `market` 필수), 성공 200 예시(`AccountSummaryResponse` 6개 필드), 오류(400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED`) 표.
  - 같은 커밋에서 세 문서를 함께 갱신(CLAUDE.md 규칙 7).

---

## 이슈 #52: 시장별 보유 종목 조회 API 구현 (PORT-001, GitHub 이슈 #52)

> 이 섹션은 `spec.md`의 PORT-001(실제 조회 API)만 다룬다. 평가 계산 자체는 이슈 #47(`HoldingValuationService`, 병합됨)을 그대로 재사용하되, "현재가" 노출을 위해 `HoldingValuationDto`에 필드 1개(`currentPrice`)를 추가한다(`plan.md` 이슈 #52 절 "DTO 확장" 참고 — 계산식·반올림·시세 무효 정책은 변경하지 않는다). 시세 무효 종목의 개별 필드 표현은 `#81`의 집계 정책과 다르게 `null` 그대로 노출하기로 결정했다(`plan.md` "시세 무효 종목의 개별 필드 표현 정책 확정" 절 근거 참고).

- [x] **`HoldingValuationDto` 확장: `currentPrice` 필드 추가 (기존 #47/#81 코드 영향 범위 포함)**
  - `HoldingValuationDto`에 `currentPrice`(`BigDecimal`, nullable) 필드 추가(`priceStatus`와 `evaluationAmount` 사이 — plan.md 레코드 정의 참고).
  - `HoldingValuationService.evaluateHolding`의 두 `new HoldingValuationDto(...)` 호출(UNAVAILABLE 분기는 `null`, AVAILABLE 분기는 `quote.price()`)을 수정.
  - **`src/test/java/com/finplay/api/account/service/AccountServiceTest.java`의 `new HoldingValuationDto(...)` 4곳을 모두 컴파일되도록 인자를 추가한다** — 기존 기대값·검증 로직은 변경하지 않는다(회귀 확인 목적).
  - `HoldingValuationServiceTest`(기존 파일)에 회귀 테스트 추가: 이익/손실 케이스 `currentPrice == quote.price()`, `UNAVAILABLE` 케이스 `currentPrice == null`.
  - `AccountService.getAccountSummary`가 named accessor만 사용해 이 확장에 영향받지 않는지 확인(`./gradlew build`로 회귀 확인).

- [x] **응답 DTO: `HoldingListItemResponse`**
  - `portfolio/dto/response/HoldingListItemResponse.java` record 추가 — `instrumentId`·`symbol`·`name`·`quantity`·`averagePrice`·`currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate`·`priceStatus` 10개 필드, 정적 팩토리 `from(Holding holding, HoldingValuationDto valuation)`(plan.md 표 참고).
  - `market`·`costBasis`는 의도적으로 제외(plan.md 근거) — 코드 리뷰 관점에서 스스로 재확인.

- [x] **Service: `HoldingService` 신규 (portfolio 도메인)**
  - `com.finplay.api.portfolio.service.HoldingService` 신규 — `AccountService`·`HoldingRepository`·`HoldingValuationService` 3개 의존성 주입(`@RequiredArgsConstructor`), `@Transactional(readOnly = true) getHoldings(Long userId, com.finplay.api.account.domain.Market market)` 추가.
  - `accountService.getAccountFor(userId, market)`로 소유권 검증 재사용 → `holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId())` → 각 `Holding`을 `holdingValuationService.evaluateHolding(holding)`으로 평가 → `HoldingListItemResponse.from(...)`으로 매핑.
  - **`evaluateActiveHoldingsForAccount`(#81이 추가한 배치 메서드)는 재사용하지 않는다** — `Holding` 엔티티(종목 표시 정보 필요)를 함께 반환하지 않으므로 기존 두 원시 메서드를 직접 조합한다(plan.md 근거).
  - 단위 테스트(`HoldingServiceTest`, 신규, Mockito): 시세 유효/무효 혼합 2건 매핑 정확성(무효 건은 4개 필드 `null`·`priceStatus="UNAVAILABLE"`), 활성 보유 없음 → 빈 리스트, 계좌 없음 → `BusinessException(NOT_FOUND)` 전파. 응답 객체를 mock으로 만들지 않고 실제 값으로 검증.

- [x] **Controller: `GET /api/holdings`**
  - 신규 패키지 `com.finplay.api.portfolio.controller`에 `HoldingController` 추가 — `@GetMapping`, `@AuthenticationPrincipal AuthenticatedUser`, `@RequestParam com.finplay.api.account.domain.Market market`(필수 — `market.domain.Market` import 금지, plan.md "입력 명세" 참고).
  - `market` 누락·잘못된 리터럴은 기존 `GlobalExceptionHandler`가 이미 400 `VALIDATION_ERROR`로 처리하므로 컨트롤러에 별도 검증 코드를 추가하지 않는다.
  - `@WebMvcTest` 슬라이스 테스트(`HoldingControllerTest`, 신규, `AccountControllerTest` 패턴 재사용): `market=STOCK`·`market=CRYPTO` 200 필드 계약(10개 필드), 보유 없음 200 빈 배열, `market` 누락 400, `market=FOREX` 400, 인증 실패 401.

- [x] **통합 테스트: 매수·매도 파이프라인 기반 보유 종목 목록 시나리오**
  - Testcontainers 기반 통합 테스트(신규 `HoldingIntegrationTest` 또는 기존 매수 통합 테스트 파일 인접)에 시나리오 추가: 2종목 매수 후 1종목 전량 매도 → `GET /api/holdings?market=` 호출 → 매도한 종목이 목록에서 제외되고 남은 종목의 6개 값(수량·평균단가·현재가·평가금액·미실현손익·수익률)이 원장·시세 기준으로 정확한지 검증(spec 완료 조건 직접 구현).
  - 보유 종목 없는 신규 계좌 → 200 빈 배열, 타인 계좌 보유가 섞이지 않음, `market` 누락/잘못된 값 400·비로그인 401 최소 1건 확인.

- [x] **문서 동기화: `docs/prd.md` · `docs/api-routes.md` · `docs/api-contracts.md`**
  - `docs/prd.md` PORT-001 절에 현재가·수익률 필드를 추가(plan.md 문서 동기화 절 참고).
  - `docs/api-routes.md` 라우트 표에 `GET /api/holdings?market=` 행 추가(Spec 컬럼에 `006 PORT-001, Issue #52` 표기).
  - `docs/api-contracts.md`에 신규 `## portfolio` 절 추가 — 요청(쿼리 `market` 필수), 성공 200 예시(`HoldingListItemResponse[]`, 시세 유효/무효 각 1건 포함), 오류(400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED`) 표. 시세 무효 항목의 표현 정책(4개 필드 `null` + `priceStatus`)을 본문에 명시.
  - 같은 커밋에서 세 문서를 함께 갱신(CLAUDE.md 규칙 7).
