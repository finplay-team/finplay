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
  `OrderStatus.CANCELLED` 추가. `Order.cancel()` 추가(`markFilled()`와 대칭 — `PENDING` 아니면 `IllegalStateException`). `Account.releaseReservedCash(long amount)` 추가(`reservedCash` 초과 시 `IllegalStateException`, `Holding.releaseReservedQuantity`와 대칭). `ErrorCode.ORDER_ALREADY_FILLED`·`ErrorCode.ORDER_ALREADY_CANCELLED`(둘 다 `HttpStatus.CONFLICT`) 추가(`IDEMPOTENCY_CONFLICT`·`UNSUPPORTED_ORDER_TYPE` 근처에 배치, 2026-08-05 사용자 확인으로 단일 `ORDER_NOT_PENDING`에서 분리). 마이그레이션 불필요(plan.md "기존 코드 현황" — `orders.status`는 이미 `VARCHAR(10)`이라 `"CANCELLED"`(9자)를 그대로 저장 가능). 단위 테스트: `OrderTest`에 `cancel()` 정상 전이·이중 취소 시 예외, `AccountTest`(또는 기존 테스트 파일)에 `releaseReservedCash` 정상 감소·초과 해제 시 예외.

- [x] 9. **`DELETE /api/orders/{orderId}` 취소 API**
  `LimitOrderCancelService`(`order.service`, plan.md "취소 흐름" 그대로 — `orderRepository.findByIdForUpdate`로 락+존재 확인 → 소유 확인 → 상태 확인 → `accountService.getAccountByIdForUpdate` → BUY는 `releaseReservedCash`, SELL은 `portfolioSellService.getHoldingForUpdate` + `releaseReservedQuantity` → `order.cancel()`). 검증 순서는 반드시 존재(404 `NOT_FOUND`) → 소유(403 `FORBIDDEN`) → 상태(409, 이미 `FILLED`면 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED`면 `ORDER_ALREADY_CANCELLED`)여야 한다. `OrderController`에 `@DeleteMapping("/{orderId}")` 추가(`Idempotency-Key` 헤더 없음, 204 응답). 서비스 단위 테스트(`LimitOrderCancelServiceTest`: BUY 취소 시 `releaseReservedCash` 호출값, SELL 취소 시 `releaseReservedQuantity` 호출값, 주문 없음 404, 타인 소유 403, 이미 `FILLED` 409 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED` 409 `ORDER_ALREADY_CANCELLED`, 검증 순서 준수) + `@WebMvcTest`(204 응답 바디 없음·404/403/409 오류 매핑, 인증 없으면 401).

- [x] 10. **동시성 경합 테스트(취소 vs 체결)**
  `LimitOrderConcurrencyIntegrationTest`에 plan.md "동시성 테스트 시나리오 추가" 그대로 2개 메서드 추가 — 기존 `runConcurrently`(ready/start `CountDownLatch`) 헬퍼를 그대로 재사용한다: (a) BUY `PENDING` 주문에 `cancelOrder`와 `fillIfPending`을 동시 호출해 정확히 한쪽만 성공(체결 승리 시 취소는 `ORDER_ALREADY_FILLED` 예외, 취소 승리 시 체결은 no-op)하고 `reservedCash`·`cashBalance`가 이중 반환·이중 소비 없이 일관됨을 검증, (b) 같은 패턴을 SELL(`reservedQuantity`·`quantity` 버전)로 1개 더 추가.

- [x] 11. **문서 동기화**
  `docs/api-routes.md`·`docs/api-contracts.md`에 `DELETE /api/orders/{orderId}` 추가(요청 없음·204 본문 없음 응답·404/403/409 오류 계약, 신규 코드 `ORDER_ALREADY_FILLED`·`ORDER_ALREADY_CANCELLED` 설명 포함) — 항목9 컨트롤러 커밋에서 이미 반영되어 있었고 실제 구현과 재대조해 일치 확인함. `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결(LMT-001~004)" 행을 "일부 완료(LMT-001~003)"로 갱신, LMT-004는 범위 밖임을 명시 — **근거는 PR 미생성으로 "이슈 #218(PR 생성 후 번호 갱신 필요)" 임시 기입, PR 생성 후 실제 번호로 교체 필요**. `./gradlew build`는 오케스트레이터가 `ErrorCodeTest`의 stale 카운트(26→27)·매핑 누락(`ORDER_NOT_PENDING`)을 직접 수정한 뒤 재실행해 통과 확인.

- [x] 12. **취소 상태(409) 오류 코드 분리(이슈 #218 후속, 2026-08-05 사용자 확인)**
  단일 `ErrorCode.ORDER_NOT_PENDING`을 `ORDER_ALREADY_FILLED`·`ORDER_ALREADY_CANCELLED`로 분리(spec.md "확정된 설계 결정" 9번). `LimitOrderCancelService.cancelOrder`의 상태 검증 분기를 `FILLED`/`CANCELLED`로 나눠 각각 다른 코드를 던지도록 수정. `ErrorCodeTest`(카운트 27→28, 매핑 엔트리 교체), `LimitOrderCancelServiceTest`·`OrderControllerTest`·`LimitOrderConcurrencyIntegrationTest`의 관련 케이스를 두 코드로 분리. `docs/api-contracts.md`·spec.md·plan.md의 `ORDER_NOT_PENDING` 서술을 모두 갱신.

## 시장가 매수 경로 락 보강 (이슈 #224)

이 절은 controller 변경이 없다 — `docs/api-routes.md`·`docs/api-contracts.md` 갱신 대상 아님(CLAUDE.md 규칙7은 controller 변경 시에만 적용). `docs/prd.md` §3 구현 현황도 갱신 대상 아님(CLAUDE.md 규칙10 "갱신 비대상" — 서비스 레이어 내부 락 순서 조정이라 기능 제공 범위가 그대로다). 신규 마이그레이션·신규 repository 메서드·신규 서비스 메서드가 전혀 없다(plan.md "기존 코드 확인 결과" — 기존 LMT-001/002 잠금 인프라를 호출부 교체만으로 재사용).

- [x] 13. **시장가 매수 계좌 락 조정**
  `OrderExecutionService.createBuyOrder`의 `Account account = getAccountFor(userId, request.market());`를 `getAccountForUpdateFor(userId, request.market())`로 교체(plan.md "변경 지점 1", 신규 메서드 없음 — SELL이 이미 쓰는 private 메서드 재사용). `getAccountForUpdateFor` 위 주석·`execute()`의 계좌 선조회 제거 주석을 "매수·매도 모두 계좌를 잠근다"로 갱신. `getAccountFor(Long, Market)`의 다른 호출부가 남아있는지 grep으로 확인 후 죽은 코드면 제거. 기존 `OrderExecutionService` 단위·슬라이스 테스트 회귀 확인(현금 부족 409 등 기존 케이스가 락 도입 후에도 그대로 통과하는지).

- [x] 14. **holdings 락 조정 (`PortfolioBuyService.applyBuyTrade`)**
  `holdingRepository.findByAccountIdAndInstrumentId(...)`를 `findByAccountIdAndInstrumentIdForUpdate(...)`로 교체(plan.md "변경 지점 2", 반환 타입 동일이라 `orElseGet` 로직 불변, 신규 repository 메서드 없음 — SELL이 이미 쓰는 락 쿼리 재사용). 메서드 위에 holdings 락·신규 종목 첫 매수는 account 락만으로 방지한다는 주석 추가(spec.md "확정된 설계 결정" 10번 인용). 락 없는 원본 `findByAccountIdAndInstrumentId`의 다른 호출부가 남아있는지 grep으로 확인. 기존 `PortfolioBuyService`·`OrderExecutionService`·`LimitOrderFillService` 단위 테스트 회귀 확인(신규 종목 첫 매수 시 holding 신규 생성 케이스 포함).

- [x] 15. **동시성 통합 테스트**
  `LimitOrderConcurrencyIntegrationTest`에 plan.md "동시성 테스트 시나리오" 3개(spec.md 시나리오 13·14·15)를 기존 `runConcurrently`(ready/start `CountDownLatch`, `ExecutorService` 2스레드) 헬퍼로 추가: (a) 시장가 매수 vs 지정가 매수 생성 계좌 경합(합산 소비액이 잔액을 초과하도록 설계, 한쪽만 성공하거나 직렬화되어 `availableCash` 불변식 유지), (b) 시장가 매수 vs 지정가 매수 체결 holdings lost-update 방지(최종 수량이 두 매수 합과 정확히 일치, `HoldingLot` 2건), (c) 조정된 시장가 매수와 기존 시장가 매도·지정가 체결 간 ABBA 데드락 회귀(타임아웃·데드락 예외 없이 완료).

- [x] 16. **spec 완료조건 확정 및 최종 빌드**
  `docs/specs/015-limit-order/spec.md` "시장가 매수 경로 락 보강 완료 조건 (이슈 #224)" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행). 이 커밋에는 `docs/api-routes.md`·`docs/api-contracts.md`·`docs/prd.md` §3 변경이 없어야 정상이다(controller·기능 제공 범위 변경 없음).

## LMT-004 미체결 주문 목록 조회 · 계좌·보유 조회 계약 영향 해소 (이슈 #235)

- [x] 17. **`GET /api/orders/pending` 목록 조회 API**
  `OrderRepositoryCustom`/`OrderRepositoryImpl`에 `findByAccountIdAndStatusWithCursor(accountId, status, cursorRequestedAt, cursorId, fetchSize)` 추가(plan.md "Repository 설계" — 기존 `findByAccountIdWithCursor`와 병렬, `status` 조건만 추가). `OrderService`에 `getMyPendingOrders(userId, market, cursor, limit)` 추가(`OrderStatus.PENDING` 고정, 기존 `getMyOrders`와 동일 구조 — `OrderCursor`·`OrderListItemResponse`·`OrderListResponse` 재사용, 신규 DTO 없음). `OrderController`에 `@GetMapping("/pending")` 추가(기존 `DEFAULT_LIMIT`/`MIN_LIMIT`/`MAX_LIMIT`·`validateLimit` 재사용). 단위 테스트(`OrderServiceTest`: 계좌 선조회, `PENDING` 상태로 리포지토리 호출 검증, 커서·`hasNext` 판정) + `@DataJpaTest`(신규 쿼리 메서드가 상태 필터·정렬·커서 조건을 만족하는지) + `@WebMvcTest`(`market`/`limit`/`cursor` 검증, 200 응답 필드 계약, 인증 실패 401).

- [x] 18. **`AccountSummaryResponse.reservedCash`·`HoldingListItemResponse.reservedQuantity` 노출**
  `AccountSummaryResponse`에 `reservedCash`(`cashBalance` 바로 다음) 필드·`of(...)` 인자 추가, `AccountService.getAccountSummary`가 `account.getReservedCash()`를 전달하도록 수정(기존 `totalValue` 등 계산식은 변경하지 않는다). `HoldingListItemResponse`에 `reservedQuantity`(`quantity` 바로 다음) 필드 추가, `of(Holding, HoldingValuationDto)`가 `holding.getReservedQuantity()`를 직접 읽도록 수정(`HoldingValuationDto`·`HoldingValuationService`는 변경하지 않는다 — 계산 로직과 예약 원장 노출은 별개 관심사, plan.md "계좌·보유 조회 계약 영향 해소" 근거). 컴파일이 깨지는 기존 테스트(`AccountServiceTest`의 `AccountSummaryResponse.of(...)`·`new HoldingValuationDto(...)` 호출부 등) 수정 포함, `reservedCash`·`reservedQuantity` 실측 검증 케이스를 `AccountServiceTest`·`HoldingServiceTest`에 각각 최소 1개 추가. `@WebMvcTest`(`AccountControllerTest`·`HoldingControllerTest`)에 `jsonPath`로 두 신규 필드 계약 검증 추가.

- [x] 19. **통합 테스트**
  지정가 매수·매도 주문을 여러 건(`PENDING`·`FILLED`·`CANCELLED` 혼합) 생성한 뒤 `GET /api/orders/pending?market=CRYPTO`가 `PENDING`만 최신순 커서 페이지네이션으로 반환하고(첫 페이지→`nextCursor`로 다음 페이지, 중복·누락 없음) 타 사용자 주문이 섞이지 않는지 검증. 같은 시나리오에서 `GET /api/accounts/summary?market=CRYPTO`·`GET /api/holdings?market=CRYPTO`를 호출해 `reservedCash`·`reservedQuantity`가 실제 예약값과 정확히 일치하고, 체결·취소 후에는 각각 0(또는 감소한 값)으로 돌아오는지 확인(plan.md "테스트 계획" 통합 시나리오 그대로, Testcontainers 기반 — ADR-0003).

- [x] 20. **문서 동기화 및 최종 빌드**
  `docs/api-routes.md`에 `GET /api/orders/pending?market=&cursor=&limit=` 행 추가. `docs/api-contracts.md`의 `## order` 절에 "미체결 주문 목록 조회" 표 추가, `## account` 절 `AccountSummaryResponse` 예시에 `reservedCash` 반영, `## portfolio` 절 `HoldingListItemResponse` 예시에 `reservedQuantity` 반영(위 세 곳 모두 같은 커밋). `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "완료"로 갱신하고, "계좌·보유 조회 계약 영향(Decision Gate)" 절 본문의 미정 문구를 확정된 필드명(`reservedCash`/`reservedQuantity`)으로 교체. `docs/specs/015-limit-order/spec.md` "LMT-004 완료 조건 (이슈 #235)" 체크박스를 구현·테스트 통과 확인 후 `[x]`로 갱신. `./gradlew build` 전체 통과 확인(실패 시 수정 후 재실행).
