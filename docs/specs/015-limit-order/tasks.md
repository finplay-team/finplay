# Tasks: 코인 지정가 매매 — 생성·체결·취소 (LMT-001~003)

각 항목 = 커밋 1개. `plan.md`의 설계를 그대로 따른다. 순서대로 구현한다(뒤 항목이 앞 항목의 산출물에 의존).

- [x] 1. **엔티티·마이그레이션·잠금 인프라**
  `db/migration/V22__add_limit_order_reservation_ledger.sql`(plan.md SQL 그대로: `accounts.reserved_cash`, `holdings.reserved_quantity`, `orders.limit_price`, `idx_orders_limit_fill`). `OrderType.LIMIT`·`OrderStatus.PENDING` 추가. `Order`에 `limitPrice` 필드·`createLimitPending` 팩토리·`markFilled()` 추가(기존 `create` 시그니처·동작 불변). `Account`에 `reservedCash`·`getAvailableCash()`·`reserveCash()`·`confirmReservedCash()` 추가. `Holding`에 `reservedQuantity`·`getAvailableQuantity()`·`reserveQuantity()`·`releaseReservedQuantity()` 추가. `AccountRepository`(`findByUserIdAndMarketForUpdate`·`findByIdForUpdate`)·`HoldingRepository`(`findByAccountIdAndInstrumentIdForUpdate`)·`OrderRepository`(`findByIdForUpdate`·`findPendingLimitOrdersToFill`)·`InstrumentRepository`(`findByMarketAndSymbol`) 추가. 단위 테스트(엔티티 불변식: 예약 초과 시 `IllegalStateException`, `markFilled` 이중 호출 방지) + `@DataJpaTest`(락 쿼리가 실제로 실행되고 값이 맞는지, Testcontainers MySQL).

- [x] 2. **`POST /api/orders/limit` 생성 API**
  `LimitOrderCreateRequest`·`LimitOrderResponse` DTO. `LimitOrderCreationService`(plan.md "지정가 생성 흐름" — BUY는 account 락만, SELL은 holding 락만). `LimitOrderService`(`OrderService` 패턴 재사용 — 선제 조회·실행·`DataIntegrityViolationException` 캐치·재조회 폴백). `OrderController`에 `POST /limit` 추가(`Idempotency-Key` 필수). 서비스 단위 테스트(현금·수량 부족 거부, 즉시체결 조건이어도 PENDING 생성, market≠CRYPTO 거부) + `@WebMvcTest`(요청 검증·직렬화·오류 매핑).

- [x] 3. **가격 갱신 이벤트 발행**
  `CryptoPriceUpdatedEvent`. `PriceStore.saveTick`에 `ApplicationEventPublisher` 주입 — 과거 틱 무시 분기를 통과해 실제로 갱신했을 때만 publish. 단위 테스트(신규 틱은 publish, 과거/동시각 틱은 미publish — 기존 MKT-003 테스트에 회귀 없는지 함께 확인).

- [x] 4. **체결 리스너·체결 서비스**
  `LimitOrderTriggerListener`(`@EventListener`, `findByMarketAndSymbol`로 종목 조회 실패 시 관용 처리, `findPendingLimitOrdersToFill`로 후보 조회, 건별 try/catch로 `limitOrderFillService.fillIfPending` 호출). `LimitOrderFillService.fillIfPending`(`@Transactional`, order→account→holding 락, BUY/SELL 체결 로직, SELL은 `RealizedPnlUpdatedEvent` 재발행). 단위 테스트(BUY 체결, SELL 체결+실현손익, 이미 FILLED인 주문은 no-op, holding 없는 신규 종목 첫 매수 체결).

- [x] 5. **기존 시장가 매도 락 순서 조정**
  `OrderExecutionService`: `execute()`에서 계좌 선조회 제거, `createSellOrder`가 `accountService.getAccountForUpdate` → `portfolioSellService.getHoldingForUpdateOrThrow` 순으로 락(매수 경로는 변경 없음). `PortfolioSellService`에 `getHoldingForUpdateOrThrow`(availableQuantity 기준) 추가, 기존 `getHoldingOrThrow` 호출부 정리(유일 호출부였는지 확인 후 제거 또는 유지 판단). 기존 시장가 매도 단위·슬라이스 테스트 회귀 확인, "예약된 수량은 시장가로 초과 매도 불가" 신규 테스트 추가.

- [x] 6. **동시성 통합 테스트**
  Testcontainers 기반 `@SpringBootTest`: (a) 동일 주문에 체결 이벤트 2회 동시 도착 시 1회만 체결, (b) 지정가 SELL 체결과 시장가 SELL 체결이 동시에 실행돼도 데드락 없음(ABBA 회귀), (c) 매수 지정가 생성 시 현금 부족 거부와 무예약 확인, (d) 매도 지정가로 예약된 수량을 시장가/다른 지정가로 초과 매도 시 거부. plan.md "동시성 테스트 시나리오" 4개 그대로 구현.

- [ ] 7. **문서 동기화**
  `docs/api-routes.md`·`docs/api-contracts.md`에 `POST /api/orders/limit` 추가(요청·응답·오류 계약, `market/api-routes.md` 41행 근처 order 도메인 절). `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결" 행을 "일부 완료(LMT-001~002)"로 갱신, 근거에 이 PR 번호 기입, LMT-003·004는 범위 밖임을 명시. `./gradlew build` 통과 확인.

## LMT-003 지정가 주문 취소 (이슈 #218)

- [x] 8. **엔티티·에러코드 확장**
  `OrderStatus.CANCELLED` 추가. `Order.cancel()` 추가(`markFilled()`와 대칭 — `PENDING` 아니면 `IllegalStateException`). `Account.releaseReservedCash(long amount)` 추가(`reservedCash` 초과 시 `IllegalStateException`, `Holding.releaseReservedQuantity`와 대칭). `ErrorCode.ORDER_NOT_PENDING(HttpStatus.CONFLICT, "이미 체결되었거나 취소된 주문입니다.")` 추가(`IDEMPOTENCY_CONFLICT`·`UNSUPPORTED_ORDER_TYPE` 근처에 배치). 마이그레이션 불필요(plan.md "기존 코드 현황" — `orders.status`는 이미 `VARCHAR(10)`이라 `"CANCELLED"`(9자)를 그대로 저장 가능). 단위 테스트: `OrderTest`에 `cancel()` 정상 전이·이중 취소 시 예외, `AccountTest`(또는 기존 테스트 파일)에 `releaseReservedCash` 정상 감소·초과 해제 시 예외.

- [x] 9. **`DELETE /api/orders/{orderId}` 취소 API**
  `LimitOrderCancelService`(`order.service`, plan.md "취소 흐름" 그대로 — `orderRepository.findByIdForUpdate`로 락+존재 확인 → 소유 확인 → 상태 확인 → `accountService.getAccountByIdForUpdate` → BUY는 `releaseReservedCash`, SELL은 `portfolioSellService.getHoldingForUpdate` + `releaseReservedQuantity` → `order.cancel()`). 검증 순서는 반드시 존재(404 `NOT_FOUND`) → 소유(403 `FORBIDDEN`) → 상태(409 `ORDER_NOT_PENDING`)여야 한다. `OrderController`에 `@DeleteMapping("/{orderId}")` 추가(`Idempotency-Key` 헤더 없음, 204 응답). 서비스 단위 테스트(`LimitOrderCancelServiceTest`: BUY 취소 시 `releaseReservedCash` 호출값, SELL 취소 시 `releaseReservedQuantity` 호출값, 주문 없음 404, 타인 소유 403, 이미 `FILLED` 409, 이미 `CANCELLED` 409, 검증 순서 준수) + `@WebMvcTest`(204 응답 바디 없음·404/403/409 오류 매핑, 인증 없으면 401).

- [x] 10. **동시성 경합 테스트(취소 vs 체결)**
  `LimitOrderConcurrencyIntegrationTest`에 plan.md "동시성 테스트 시나리오 추가" 그대로 2개 메서드 추가 — 기존 `runConcurrently`(ready/start `CountDownLatch`) 헬퍼를 그대로 재사용한다: (a) BUY `PENDING` 주문에 `cancelOrder`와 `fillIfPending`을 동시 호출해 정확히 한쪽만 성공(체결 승리 시 취소는 `ORDER_NOT_PENDING` 예외, 취소 승리 시 체결은 no-op)하고 `reservedCash`·`cashBalance`가 이중 반환·이중 소비 없이 일관됨을 검증, (b) 같은 패턴을 SELL(`reservedQuantity`·`quantity` 버전)로 1개 더 추가.

- [x] 11. **문서 동기화**
  `docs/api-routes.md`·`docs/api-contracts.md`에 `DELETE /api/orders/{orderId}` 추가(요청 없음·204 본문 없음 응답·404/403/409 오류 계약, 신규 코드 `ORDER_NOT_PENDING` 설명 포함) — 항목9 컨트롤러 커밋에서 이미 반영되어 있었고 실제 구현과 재대조해 일치 확인함. `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결(LMT-001~004)" 행을 "일부 완료(LMT-001~003)"로 갱신, LMT-004는 범위 밖임을 명시 — **근거는 PR 미생성으로 "이슈 #218(PR 생성 후 번호 갱신 필요)" 임시 기입, PR 생성 후 실제 번호로 교체 필요**. `./gradlew build`는 오케스트레이터가 `ErrorCodeTest`의 stale 카운트(26→27)·매핑 누락(`ORDER_NOT_PENDING`)을 직접 수정한 뒤 재실행해 통과 확인.
