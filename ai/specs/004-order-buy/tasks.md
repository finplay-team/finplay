# Tasks: 시장가 매수 (검증 · 수수료 · 멱등성 · lot 생성)

- [x] **1. 타 도메인 확장 지점 준비 (account·market·common)**
  - `Account`에 `deductCash(long amount)` 추가(단위 테스트: 정상 차감, 방어적 예외 케이스).
  - `AccountRepository.findByUserIdAndMarket(Long userId, Market market)` 추가 + `AccountService.getAccountFor(Long userId, Market market)` 추가(`@DataJpaTest`로 리포지토리 조회, 서비스는 단위 테스트로 미존재 시 `NOT_FOUND`).
  - `PriceQueryService.assertOrderable(Instrument)` 추가 — 주식 장외 `MARKET_CLOSED`, 그 외 통과(단위 테스트: 주식 OPEN/CLOSED 분기, 코인은 항상 통과).
  - `GlobalExceptionHandler`에 `MissingRequestHeaderException`을 기존 400 매핑 목록에 추가(슬라이스 테스트로 헤더 누락 시 400 확인 — 임시 테스트 컨트롤러 또는 3번 컨트롤러 완료 후 함께 검증).

- [x] **2. 보유(Holding)·lot 갱신 서비스 (portfolio 도메인)**
  - `Holding.applyBuy(BigDecimal quantity, BigDecimal price, LocalDateTime now)` 추가 — 가중평균 단가 재계산, `isActive=true` 전환.
  - `HoldingRepository.findByAccountIdAndInstrumentId(Long accountId, Long instrumentId)` 추가.
  - `PortfolioBuyService.applyBuyTrade(Account, Instrument, Trade buyTrade, BigDecimal quantity, BigDecimal price, long fee, LocalDateTime now)` 신규 — holding 조회/생성 → `applyBuy` → `HoldingLot.create` 저장.
  - 단위 테스트: 신규 보유 생성(평균단가=체결가), 기존 보유 추가매수 시 가중평균 재계산, lot 최초수량=잔여수량=체결수량.

- [x] **3. OrderService — 검증·체결·트랜잭션**
  - `OrderCreateRequest`(record, `market`·`instrumentId`·`side`·`orderType`(String)·`quantity`) + Bean Validation.
  - `OrderService.createBuyOrder(Long userId, String idempotencyKey, OrderCreateRequest request)` — plan.md 순서대로: 헤더 존재(컨트롤러에서 이미 보장) → `orderType=="MARKET"` 검증(아니면 422) → `side==BUY` 검증(아니면 400, 미확정 항목 참조) → instrument 조회(404) → 시장 일치 검증(400) → 수량 형식·최소금액 검증(400) → `priceQueryService.assertOrderable` → `priceQueryService.getPrice` → 수수료·거래금액 계산(plan.md 반올림 규칙) → 현금 검증(409 INSUFFICIENT_CASH) → `Order.create`/`Trade.of` 저장 → `account.deductCash` → `PortfolioBuyService.applyBuyTrade` → 응답 조립. 전체를 `@Transactional`로 묶는다.
  - 요청 해시(SHA-256) 계산 후 `Order.create`에 전달.
  - 단위 테스트(Mockito): plan.md 테스트 계획의 성공·모든 실패 케이스 + "실패 시 저장 계열 mock 미호출" 검증.

- [x] **4. OrderController + 응답 DTO**
  - `OrderResponse.of(Order, Trade)` 정적 팩토리.
  - `OrderController` — `POST /api/orders`, `@AuthenticationPrincipal AuthenticatedUser`, `@RequestHeader("Idempotency-Key") String`(required), `@Valid @RequestBody OrderCreateRequest`, 성공 201.
  - `@WebMvcTest(OrderController.class)` — 성공 응답 필드(jsonPath), `Idempotency-Key` 누락 400, 잘못된 `market`/`side` 리터럴 400(Jackson 파싱 실패), `orderType` 미지원 값 422(서비스 mock이 예외 던지도록 스텁), 최소 1개 실패코드 경로.

- [x] **5. 통합 테스트 (Testcontainers, 핵심 시나리오)**
  - `OrderBuyIntegrationTest`(`@SpringBootTest` + Testcontainers MySQL) — 주식 매수 성공 시 주문·체결·현금차감·holding(신규)·lot이 한 트랜잭션으로 저장되는지, 현금 부족 시 4개 테이블 모두 흔적 없는지, 같은 계좌·종목 재매수 시 평균단가 재계산과 lot 2건 생성.
  - `./gradlew build` 전체 통과 확인(스팟버그스·JaCoCo 포함).

- [x] **6. 문서 갱신**
  - `ai/api-routes.md`에 `POST /api/orders` 라우트 추가.
  - `docs/api-contracts.md`에 요청·응답·오류 코드(400/404/409/422 각 사유) 계약 추가.
  - 같은 커밋에서 두 문서를 함께 갱신(동기화 모드).

## 이슈 #22 — 멱등성 재요청 응답 재현

> plan.md "이슈 #22 — ORD-006 멱등성 재요청 응답 재현" 절 참조. 새 마이그레이션 없음(V10 `uk_orders_user_idempotency` 재사용). 매수·매도(#41) 모두 대상.

- [x] **1. 리포지토리 확장**
  - `OrderRepository.findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey)` 추가 — `JOIN FETCH o.instrument` 포함(plan.md 확정 쿼리 그대로).
  - `TradeRepository.findByOrderId(Long orderId)` 추가(파생 쿼리).
  - `OrderRepositoryTest`(`@DataJpaTest`)에 신규 메서드 케이스 추가: 존재, 미존재, 다른 사용자가 동일 `idempotencyKey`를 써도 조회되지 않음.
  - 신규 `TradeRepositoryTest`(`@DataJpaTest`): `findByOrderId` 존재/미존재.

- [x] **2. `OrderExecutionService` 추출 (리팩터링, 동작 변경 없음)**
  - 기존 `OrderService`의 검증·가격조회·체결·저장 로직 전체(`createOrder`→`execute`로 개명, `createBuyOrder`·`createSellOrder`·`validateOrderType`·`getValidatedInstrument`·`validateQuantityFormat`·`getAccountFor`·`priceOrder`·`validateMinOrderAmount`·`OrderPricing`)를 새 클래스 `com.finplay.api.order.service.OrderExecutionService`로 그대로 이동. `execute`는 `requestHash`를 파라미터로 받도록 시그니처 변경(내부 재계산 제거), `calculateRequestHash`는 `OrderService`로 이동.
  - 기존 `OrderServiceTest`(494줄)를 `OrderExecutionServiceTest`로 이름 변경 + 대상 교체, 호출부만 `execute(userId, idempotencyKey, requestHash, request)`로 수정(케이스 내용·검증 로직은 그대로). 리팩터링 전후로 매수·매도 기존 테스트가 전부 그대로 통과함을 확인(회귀 없음).

- [x] **3. `OrderService` 멱등성 오케스트레이션**
  - `createOrder`(plan.md 확정 로직 그대로): `calculateRequestHash` → `findReplayResponse`(일치 시 재구성 응답 반환, 불일치 시 즉시 409 `IDEMPOTENCY_CONFLICT`, 미존재 시 빈 값) → 미존재면 `orderExecutionService.execute(...)` 호출 → `DataIntegrityViolationException` 캐치 시 `findReplayResponse` 재시도, 그래도 못 찾으면 409.
  - 새 슬림 `OrderServiceTest`(`OrderExecutionService`·`OrderRepository`·`TradeRepository` mock): 재요청 동일 본문(재구성 응답, `execute` 미호출), 재요청 다른 본문(409, `execute` 미호출), 신규 키(execute 1회 호출·결과 전달), `execute`가 `DataIntegrityViolationException` 던지고 재조회 성공(재구성 응답, `execute` 1회만 호출됨 확인), `execute`가 `DataIntegrityViolationException` 던지고 재조회 실패(409, 방어적 케이스).

- [x] **4. 통합 테스트**
  - 기존 `OrderBuyIntegrationTest`/`OrderSellIntegrationTest`에 케이스 추가 또는 신규 `OrderIdempotencyIntegrationTest`(Testcontainers): 동일 키+동일 본문 재요청(매수·매도 각 1케이스)이 `orders`/`trades`/`holdings`/`holding_lots` 행을 추가하지 않고 최초와 동일한 응답을 반환하는지, 동일 키+다른 본문이 409 `IDEMPOTENCY_CONFLICT`인지, 서로 다른 사용자가 같은 키를 써도 각자 정상 체결되는지.
  - `./gradlew build` 전체 통과 확인(스팟버그스·JaCoCo 포함).

- [x] **5. 문서 갱신**
  - `docs/api-contracts.md`의 `POST /api/orders` 계약에서 "헤더 존재 검증까지만·#22에서 구현 예정" 문구를 제거하고 재요청 재현·409 `IDEMPOTENCY_CONFLICT` 규칙을 명시, 근거에 Issue #22 추가.
  - `ai/api-routes.md`의 같은 라우트 설명에서 "재현 방지는 #22" 문구를 갱신.
  - 같은 커밋에서 두 문서를 함께 갱신(동기화 모드).
