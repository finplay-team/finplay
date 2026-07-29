# Tasks: 시장가 매도 (FIFO lot 소비 · 실현손익)

- [ ] **1. 엔티티·Repository 확장 지점 (order·account·portfolio 도메인)**
  - `Trade`(order.domain)에 `fillRealizedPnl(long realizedPnl)` 추가 — 이미 값이 있으면 `IllegalStateException`(plan.md 설계 노트 4·5).
  - `Account`(account.domain)에 `addCash(long amount)`·`addRealizedPnl(long amount)` 추가(plan.md 설계 노트 5).
  - `Holding`(portfolio.domain)에 `applySell(BigDecimal quantity, LocalDateTime now)` 추가 — 초과 차감 시 `IllegalStateException`, 0이 되면 `isActive=false`, `averagePrice` 불변(plan.md 설계 노트 5, 미확정 섹션 참조).
  - `HoldingLot`(portfolio.domain)에 `consume(BigDecimal quantity)` 추가 — 초과 소비 시 `IllegalStateException`(plan.md 설계 노트 5).
  - `HoldingLotRepository`에 `findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(Long holdingId, BigDecimal remainingQuantity)` 추가(plan.md 설계 노트 6).
  - `TradeAllocationRepository`에 `sumAllocatedCostByHoldingLotId`·`sumAllocatedBuyFeeByHoldingLotId` JPQL 쿼리 추가(plan.md 설계 노트 6).
  - 단위 테스트: 각 엔티티 메서드의 정상·경계(정확히 소진)·초과(예외) 케이스. Repository 2종은 `@DataJpaTest` 슬라이스로 FIFO 정렬(실행시각 오름차순, 동시각 id 오름차순)과 SUM 집계(배분 없음=0, 여러 건 합산) 검증.

- [ ] **2. `PortfolioSellService` — FIFO 배분·실현손익 원가 계산 (portfolio 도메인)**
  - `PortfolioSellService`(신규, `portfolio.service`) — `getHoldingOrThrow(Account, Instrument, BigDecimal requiredQuantity)`(없음/부족 시 `BusinessException(INSUFFICIENT_QTY)`), `applySellTrade(Holding, Trade sellTrade, BigDecimal sellQuantity, LocalDateTime now)`(plan.md 설계 노트 3 의사코드 그대로 — FIFO lot 조회 → 배분 수량 결정 → lot 소진 여부에 따른 원가·수수료 분기 계산 → `HoldingLot.consume`·`TradeAllocation.create`·`Holding.applySell` 반영).
  - `SellAllocationDto(long totalAllocatedCost, long totalAllocatedBuyFee)` 신규(`portfolio.service`).
  - 단위 테스트(`PortfolioSellServiceTest`, mock repository): FIFO 단일 lot 완전 소진, 다중 lot 소비(먼저 산 lot부터, 배분 2건 이상), 정확히 소진(원가·수수료 잔여가 마지막 배분에 정확히 흡수되는지 — 나눠떨어지지 않는 수량으로 경계값 케이스 포함), 일부만 남기고 매도(lot의 `remainingQuantity` > 0 유지), 보유 없음/부족 시 예외.

- [ ] **3. `OrderService` — SELL 분기 통합 (createBuyOrder → createOrder)**
  - `createBuyOrder`를 `createOrder(Long userId, String idempotencyKey, OrderCreateRequest request)`로 이름 변경, `side` 분기 구조로 리팩터링(plan.md 설계 노트 1·2). 기존 400 임시 거부 분기(`side != BUY`) 제거.
  - 공유 검증(주문유형·종목조회·시장일치·수량형식·계좌조회·최소주문금액)을 private 메서드로 추출해 BUY·SELL 양쪽에서 재사용.
  - SELL 흐름: `portfolioSellService.getHoldingOrThrow` → 가격조회 → 금액·수수료 계산 → `Order.create`/`Trade.of`(realizedPnl=null) 저장 → `portfolioSellService.applySellTrade` → `realizedPnl` 계산(plan.md 설계 노트 4 공식) → `trade.fillRealizedPnl` → `account.addCash`/`addRealizedPnl` → 응답 조립. 전체 `@Transactional` 유지.
  - 단위 테스트(`OrderServiceTest` 확장): 기존 BUY 테스트를 `createOrder(...)` 호출로 갱신 + SELL 성공(단일/다중 lot 소비, 실현손익값), 보유수량 초과 409(저장 계열 mock 미호출 검증), 코인 최소주문금액 미달(미확정 섹션 결정 반영), 그 외 공유 검증(주문유형·시장불일치·수량형식) SELL 경로에서도 동작 확인.

- [ ] **4. `OrderController` + `OrderResponse` — 응답에 실현손익 노출**
  - `OrderController`가 `orderService.createOrder(...)`를 호출하도록 변경(메서드명만 변경, 나머지 시그니처 동일).
  - `OrderResponse`에 `Long realizedPnl` 필드 추가, `of(Order, Trade)`가 `trade.getRealizedPnl()` 전달(plan.md 설계 노트 8).
  - `@WebMvcTest(OrderControllerTest)` 확장: SELL 요청 201 + `realizedPnl` `jsonPath` 검증, BUY 응답은 `realizedPnl: null` 유지 확인.

- [ ] **5. 통합 테스트 (Testcontainers, 핵심 시나리오)**
  - `OrderSellIntegrationTest`(신규, `@SpringBootTest` + Testcontainers MySQL, `OrderBuyIntegrationTest`와 동일한 재생세션·시세 픽스처 구성 재사용) — spec.md 완료조건 그대로: 다중 매수 후 부분 매도 시 FIFO 순서로 lot 차감·배분 저장, 하나의 매도가 여러 lot을 소비하는 케이스의 배분·실현손익 값, 실현손익 공식 검증, 보유수량 초과 매도 409 + 6개 테이블(orders·trades·holdings·holding_lots·trade_allocations·accounts) 무흔적, 전량 매도 시 holding 잔량 0(`isActive=false`).
  - `./gradlew build` 전체 통과 확인(스팟버그스·JaCoCo 포함).

- [ ] **6. 문서 갱신**
  - `docs/api-routes.md`의 `POST /api/orders` 요약을 SELL 포함으로 갱신(현재 "인증 사용자의 시장가 매수 주문을..." 문구 → 매수·매도 공통 문구, 근거에 이슈 #41 추가).
  - `docs/api-contracts.md` 222행 계약을 갱신 — 응답 예시에 `realizedPnl` 필드 추가(BUY는 `null`, SELL 예시 응답 별도 제시), 오류 목록에서 "`side=SELL`은 400 `VALIDATION_ERROR`" 문구 제거 후 409 `INSUFFICIENT_QTY`(보유수량 초과) 추가.
  - 같은 커밋에서 두 문서를 함께 갱신(동기화 모드, planner 재투입).
