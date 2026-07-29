# Tasks: 시장가 매수 (검증 · 수수료 · 멱등성 · lot 생성)

- [ ] **1. 타 도메인 확장 지점 준비 (account·market·common)**
  - `Account`에 `deductCash(long amount)` 추가(단위 테스트: 정상 차감, 방어적 예외 케이스).
  - `AccountRepository.findByUserIdAndMarket(Long userId, Market market)` 추가 + `AccountService.getAccountFor(Long userId, Market market)` 추가(`@DataJpaTest`로 리포지토리 조회, 서비스는 단위 테스트로 미존재 시 `NOT_FOUND`).
  - `PriceQueryService.assertOrderable(Instrument)` 추가 — 주식 장외 `MARKET_CLOSED`, 그 외 통과(단위 테스트: 주식 OPEN/CLOSED 분기, 코인은 항상 통과).
  - `GlobalExceptionHandler`에 `MissingRequestHeaderException`을 기존 400 매핑 목록에 추가(슬라이스 테스트로 헤더 누락 시 400 확인 — 임시 테스트 컨트롤러 또는 3번 컨트롤러 완료 후 함께 검증).

- [ ] **2. 보유(Holding)·lot 갱신 서비스 (portfolio 도메인)**
  - `Holding.applyBuy(BigDecimal quantity, BigDecimal price, LocalDateTime now)` 추가 — 가중평균 단가 재계산, `isActive=true` 전환.
  - `HoldingRepository.findByAccountIdAndInstrumentId(Long accountId, Long instrumentId)` 추가.
  - `PortfolioBuyService.applyBuyTrade(Account, Instrument, Trade buyTrade, BigDecimal quantity, BigDecimal price, long fee, LocalDateTime now)` 신규 — holding 조회/생성 → `applyBuy` → `HoldingLot.create` 저장.
  - 단위 테스트: 신규 보유 생성(평균단가=체결가), 기존 보유 추가매수 시 가중평균 재계산, lot 최초수량=잔여수량=체결수량.

- [ ] **3. OrderService — 검증·체결·트랜잭션**
  - `OrderCreateRequest`(record, `market`·`instrumentId`·`side`·`orderType`(String)·`quantity`) + Bean Validation.
  - `OrderService.createBuyOrder(Long userId, String idempotencyKey, OrderCreateRequest request)` — plan.md 순서대로: 헤더 존재(컨트롤러에서 이미 보장) → `orderType=="MARKET"` 검증(아니면 422) → `side==BUY` 검증(아니면 400, 미확정 항목 참조) → instrument 조회(404) → 시장 일치 검증(400) → 수량 형식·최소금액 검증(400) → `priceQueryService.assertOrderable` → `priceQueryService.getPrice` → 수수료·거래금액 계산(plan.md 반올림 규칙) → 현금 검증(409 INSUFFICIENT_CASH) → `Order.create`/`Trade.of` 저장 → `account.deductCash` → `PortfolioBuyService.applyBuyTrade` → 응답 조립. 전체를 `@Transactional`로 묶는다.
  - 요청 해시(SHA-256) 계산 후 `Order.create`에 전달.
  - 단위 테스트(Mockito): plan.md 테스트 계획의 성공·모든 실패 케이스 + "실패 시 저장 계열 mock 미호출" 검증.

- [ ] **4. OrderController + 응답 DTO**
  - `OrderResponse.of(Order, Trade)` 정적 팩토리.
  - `OrderController` — `POST /api/orders`, `@AuthenticationPrincipal AuthenticatedUser`, `@RequestHeader("Idempotency-Key") String`(required), `@Valid @RequestBody OrderCreateRequest`, 성공 201.
  - `@WebMvcTest(OrderController.class)` — 성공 응답 필드(jsonPath), `Idempotency-Key` 누락 400, 잘못된 `market`/`side` 리터럴 400(Jackson 파싱 실패), `orderType` 미지원 값 422(서비스 mock이 예외 던지도록 스텁), 최소 1개 실패코드 경로.

- [ ] **5. 통합 테스트 (Testcontainers, 핵심 시나리오)**
  - `OrderBuyIntegrationTest`(`@SpringBootTest` + Testcontainers MySQL) — 주식 매수 성공 시 주문·체결·현금차감·holding(신규)·lot이 한 트랜잭션으로 저장되는지, 현금 부족 시 4개 테이블 모두 흔적 없는지, 같은 계좌·종목 재매수 시 평균단가 재계산과 lot 2건 생성.
  - `./gradlew build` 전체 통과 확인(스팟버그스·JaCoCo 포함).

- [ ] **6. 문서 갱신**
  - `docs/api-routes.md`에 `POST /api/orders` 라우트 추가.
  - `docs/api-contracts.md`에 요청·응답·오류 코드(400/404/409/422 각 사유) 계약 추가.
  - 같은 커밋에서 두 문서를 함께 갱신(동기화 모드).
