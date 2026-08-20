# API 계약 — order

`docs/api-contracts.md`의 order 도메인 절(및 021 일반 리스크관리 OCO 절)을 옮겨 정리한 문서다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

### 시장가 매수·매도 주문 생성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/orders | Access Bearer 필수, Header `Idempotency-Key` 필수(`OrderCreateRequest`와 별도) | 매수 `{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}`, 매도 `{"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","quantity":"10"}` (`market`은 `STOCK`\|`CRYPTO` 리터럴만 파싱 성공, `side`는 `BUY`\|`SELL` 리터럴만 파싱 성공, `orderType`은 문자열, `quantity`는 문자열 숫자) | BUY 201 `{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:00:00","tradeId":1,"price":70000,"amount":700000,"fee":105,"realizedPnl":null,"executedAt":"2026-07-29T09:00:00"}`; SELL 201 `{"orderId":2,"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:05:00","tradeId":2,"price":71000,"amount":710000,"fee":106,"realizedPnl":9895,"executedAt":"2026-07-29T09:05:00"}` (둘 다 `OrderResponse`) — BUY는 `realizedPnl`이 항상 `null`, SELL은 부호 있는 정수(손실은 음수). 동일 `Idempotency-Key`+동일 요청 본문으로 재요청하면 새로 체결하지 않고 최초 응답을 그대로 재구성해 동일하게 201로 반환한다 | `Idempotency-Key` 누락, `market`\|`side` 미지원 리터럴(Jackson 파싱 실패), 수량 형식 위반(주식 소수·코인 8자리 초과·0 이하), 코인 최소주문금액(5,000원) 미달(매수·매도 공통 적용), 요청 시장≠종목 시장은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND`. 주식 장외는 409 `MARKET_CLOSED`, 유효한 최신 가격 없음은 409 `PRICE_UNAVAILABLE`(코인은 연결 끊김이거나 시세를 한 번도 받은 적이 없을 때만 — 연결 유지 + 수신 이력이 있으면 관측 시각이 얼마나 오래됐어도 마지막 가격으로 체결되며 더 이상 거부 사유가 아니다, 036), 거래 불가 종목(`tradable=false`, 예: 031 튜토리얼 샘플 종목 2·3번)은 409 `INSTRUMENT_NOT_TRADABLE`, 현금 부족(BUY)은 409 `INSUFFICIENT_CASH`(047 이후 샌드박스 종목이면 튜토리얼 계좌 기준으로 판정해 409 `TUTORIAL_INSUFFICIENT_CASH`), 보유 없음 또는 요청수량이 예약 가능 수량(`availableQuantity = quantity - reservedQuantity`)을 초과(SELL)하면 409 `INSUFFICIENT_QTY`, 동일 `Idempotency-Key`로 다른 요청 본문을 보내거나(요청 해시 불일치) 동시 요청 경합 시 최초 응답 재구성마저 실패하면 409 `IDEMPOTENCY_CONFLICT`. `orderType != "MARKET"`(예: `"LIMIT"`)은 422 `UNSUPPORTED_ORDER_TYPE` 공통 오류 형식 | 004 ORD-001~004, 005 ORD-001~006, 015 LMT-001, 016 candidate 6, 034, Issue #13, Issue #41, Issue #22, Issue #369 |

계좌·주문자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. `OrderService.createOrder`는 요청 해시를 계산한 뒤 `(userId, idempotencyKey)`로 기존 주문을 먼저 조회한다(애플리케이션 레벨 선제 조회) — 기존 주문이 있고 요청 해시가 같으면 그 주문·체결을 다시 읽어 조립한 `OrderResponse`를 검증·체결 로직을 전혀 타지 않고 그대로 반환하며(재요청도 상태코드 201 포함 최초 응답과 동일), 요청 해시가 다르면 즉시 409 `IDEMPOTENCY_CONFLICT`다. 기존 주문이 없으면 신규 생성 경로(`OrderExecutionService.execute`)로 진행하되, 두 요청이 진짜 동시에 들어와 DB 유니크 제약(`uk_orders_user_idempotency`, `V10` 마이그레이션에 이미 존재)에 걸리는 경합만 예외적으로 저장 직후 재조회를 재시도해 가능하면 최초 응답을 재구성하고, 그래도 찾지 못하면 방어적으로 409 `IDEMPOTENCY_CONFLICT`다. 서로 다른 사용자가 같은 키를 사용해도 조회·제약 모두 `userId`로 스코프가 분리되어 있어 서로 간섭하지 않는다.

SELL은 가격을 조회하기 전에 보유수량부터 검증한다(불필요한 시세 조회 회피 — 보유 없음 또는 요청수량이 예약 가능 수량(`availableQuantity = quantity - reservedQuantity`)을 초과하면 409 `INSUFFICIENT_QTY`, 이 시점까지 어떤 것도 저장하지 않는다). 지정가 매도(LMT-001) 예약으로 걸린 수량은 이 검증에서 매도 가능분으로 잡히지 않는다(`PortfolioSellService.getHoldingForUpdateOrThrow`, 016 candidate 6). 이후 보유 lot을 FIFO(체결시각 오름차순, 동시각은 `id` 오름차순)로 소비해 `trade_allocations`에 배분을 저장하고, `realizedPnl = (매도금액 - 매도수수료) - (배분된 매수원가 합 + 배분된 매수수수료 합)`을 계산해 `trades.realized_pnl`·`accounts.realized_pnl`에 반영한다. `Holding.averagePrice`는 매도로 갱신되지 않으며, 전량 매도로 보유수량이 0이 되면 `holding.isActive`는 `false`가 된다.

검증·시세·현금·보유수량 부족 등 모든 실패 경로는 주문·체결·계좌·보유·lot·배분 테이블에 어떤 흔적도 남기지 않는다(하나의 `@Transactional` 롤백).

### 코인 지정가 매수·매도 주문 생성 (LMT-001)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/orders/limit | Access Bearer 필수, Header `Idempotency-Key` 필수(`LimitOrderCreateRequest`와 별도) | 매수 `{"market":"CRYPTO","instrumentId":1,"side":"BUY","quantity":"0.1","limitPrice":"70000000"}`, 매도 `{"market":"CRYPTO","instrumentId":1,"side":"SELL","quantity":"0.1","limitPrice":"70000000"}` (`market`은 `CRYPTO`만 허용, `side`는 `BUY`\|`SELL` 리터럴만 파싱 성공, `quantity`·`limitPrice`는 문자열 숫자, 둘 다 필수) | 매수·매도 공통 201 `{"orderId":1,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":70000000,"requestedAt":"2026-08-05T09:00:00"}` (`LimitOrderResponse`) — `orderType`은 항상 `"LIMIT"`, `status`는 항상 `"PENDING"`(생성 시점에 체결하지 않으므로 `Trade`가 없다). 즉시체결 조건(매수 지정가≥현재가, 매도 지정가≤현재가)을 충족해도 생성 시점에 거부하지 않는다 — 체결은 `PriceStore` 가격 갱신 트리거(LMT-002)에서만 발생. 영속 attempt가 있고 선택된 튜토리얼 샘플의 BUY·SELL은 account/holding보다 먼저 현재 attempt를 잠그고 서버가 현재 run에 귀속한다. attempt가 없는 기존 샘플과 실제 종목 주문의 귀속 컬럼은 null이다. 동일 `Idempotency-Key`+동일 요청 본문으로 재요청하면 새로 예약하지 않고 최초 응답을 그대로 재구성해 동일하게 201로 반환한다 | `Idempotency-Key` 누락, `market != "CRYPTO"`("코인 종목만 지정가 주문을 지원합니다."), `side` 미지원 리터럴(Jackson 파싱 실패), 수량 형식 위반(코인 8자리 초과·0 이하), 지정가 0 이하, 코인 최소주문금액(5,000원, `수량×지정가` 기준) 미달, 요청 시장≠종목 시장은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND`. 예약 가능 현금(`cashBalance-reservedCash`) 부족(BUY)은 409 `INSUFFICIENT_CASH`(047 이후 샌드박스 종목이면 튜토리얼 계좌 기준으로 판정해 409 `TUTORIAL_INSUFFICIENT_CASH`), 예약 가능 수량(`quantity-reservedQuantity`) 부족 또는 보유 없음(SELL)은 409 `INSUFFICIENT_QTY`, 영속 attempt가 있는데 현재 선택 종목/run이 아니면 409 `PRACTICE_STEP_LOCKED`, 완료 attempt면 409 `PRACTICE_ALREADY_COMPLETED`, 동일 `Idempotency-Key`로 다른 요청 본문을 보내거나 동시 요청 경합 시 최초 응답 재구성마저 실패하면 409 `IDEMPOTENCY_CONFLICT` | 015 LMT-001, Issue #210; 039 TUTORIAL-FLOW-003, Issue #378 |

`POST /api/orders`(시장가 전용)의 `orderType="LIMIT"` 422 `UNSUPPORTED_ORDER_TYPE` 거부는 이 엔드포인트 추가와 무관하게 그대로 유지된다 — 두 경로가 영구히 공존한다.

`LimitOrderService.createLimitOrder`는 `POST /api/orders`(`OrderService.createOrder`)와 동일한 멱등성 패턴을 재사용한다 — 요청 해시 계산 → `(userId, idempotencyKey)` 선제 조회(있고 해시 일치 시 `Order`만으로 응답 재구성, 불일치 시 즉시 409) → 신규 생성 경로(`LimitOrderCreationService.execute`) → 유니크 제약(`uk_orders_user_idempotency`) 경합 시 저장 직후 재조회 폴백.

예약(에스크로) 로직은 매수·매도가 다른 자원만 잠근다(plan.md 잠금 순서 표) — **BUY**는 계좌만 잠그고(`AccountService.getAccountForUpdate`) `cashRequired = FLOOR(수량×지정가) + FLOOR(FLOOR(수량×지정가)×0.05%)`를 `availableCash`와 비교해 부족하면 저장 전 거부, 통과하면 `Account.reserveCash`로 예약만 하고(`cashBalance`는 불변) `Order.createLimitPending`을 저장한다. **SELL**은 계좌 락 없이 holding만 잠그고(`PortfolioSellService.getHoldingForUpdateOrThrow`, `availableQuantity` 기준 검증) `Holding.reserveQuantity`로 수량만 예약한다(계좌 현금은 건드리지 않음). 두 경로 모두 실패 시 아무것도 예약·저장하지 않는다(하나의 `@Transactional` 롤백).

### 코인 지정가 주문 취소 (LMT-003)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/orders/{orderId} | Access Bearer 필수, `Idempotency-Key` 헤더 불필요(DELETE는 멱등, 재호출은 상태 검증으로 자연히 409 거부) | 없음(경로 변수 `orderId`만) | 204, 본문 없음(`DELETE /api/community/posts/{postId}`·`DELETE /api/community/comments/{commentId}` 컨벤션 재사용) — 매수 취소는 `accounts.reserved_cash`가 해당 주문이 예약했던 만큼 정확히 감소(`cashBalance` 불변), 매도 취소는 `holdings.reserved_quantity`가 정확히 감소(`quantity` 불변), 주문 `status`는 `CANCELLED`로 변경 | `orderId`에 해당하는 주문 없음은 404 `NOT_FOUND`. 존재하지만 요청자 소유가 아니면 403 `FORBIDDEN`(소유하지 않은 주문의 상태·시장가/지정가 여부를 흘리지 않음). 본인 소유이지만 이미 `FILLED`면 409 `ORDER_ALREADY_FILLED`(신규 코드), 이미 `CANCELLED`면 409 `ORDER_ALREADY_CANCELLED`(신규 코드). 검증 순서는 존재(404)→소유(403)→상태(409) 고정. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 015 LMT-003, Issue #218 |

교육 세션 귀속 주문(`practicePriceSessionId` non-null)도 필터 없이 동일하게 조회·취소 가능하다(`findByIdForUpdate`가 `practicePriceSessionId`로 구분하지 않음, 030 이슈 #320 사전 분석에서 예견된 의도된 동작).

시장가 주문(`orderType=MARKET`)은 생성 즉시 `FILLED`이므로 이 엔드포인트로 취소를 시도하면 상태 검증(409 `ORDER_ALREADY_FILLED`)에서 자연히 걸러진다 — 별도 `orderType` 분기 없이 상태만으로 시장가 주문을 배제한다. `LimitOrderCancelService.cancelOrder`는 `orderRepository.findByIdForUpdate`로 주문 락+존재 확인을 동시에 수행한 뒤(LMT-002 `fillIfPending`과 같은 지점) 소유·상태를 순서대로 검증하고, `accountService.getAccountByIdForUpdate`로 계좌를 잠근다(잠금 순서는 LMT-002 체결과 동일한 `order → account → (SELL만) holding`). BUY는 `Account.releaseReservedCash`, SELL은 `PortfolioSellService.getHoldingForUpdate` + `Holding.releaseReservedQuantity`로 예약만 되돌리고 실제 `cashBalance`·`quantity`는 건드리지 않는다(애초에 체결되지 않았으므로). 체결 트리거(LMT-002)와 취소가 동시에 도착해도 두 흐름 모두 order를 가장 먼저 잠그므로, 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 락 대기 후 `status != PENDING`을 보고 자기 작업을 거부/no-op한다 — 예약이 이중으로 반환되거나 이중으로 소비되지 않는다. 이미 `FILLED`/이미 `CANCELLED`를 하나의 `ORDER_NOT_PENDING`으로 묶지 않고 `ORDER_ALREADY_FILLED`/`ORDER_ALREADY_CANCELLED`로 분리한 이유는 클라이언트가 두 사유를 구분해 다른 안내를 보여줄 수 있어야 한다는 사용자 판단(2026-08-05, spec.md "확정된 설계 결정" 9번)이다.

### 지정가 주문 수정 (LMT-005)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/orders/{orderId} | Access Bearer 필수, `Idempotency-Key` 헤더 불필요(변경 후 값을 절대값으로 지정하는 요청이라 자연 멱등 — `DELETE /api/orders/{orderId}`와 같은 이유) | `LimitOrderUpdateRequest`: `limitPrice`·`quantity` 둘 다 nullable `BigDecimal`, 부분 갱신 허용(예: `{"limitPrice":"75000000"}`만 보내면 `quantity`는 기존값 유지). 둘 다 생략(둘 다 `null`)하면 요청 자체를 거부 | 200 `{"orderId":1,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":75000000,"requestedAt":"2026-08-05T09:00:00"}` (`LimitOrderResponse`, `POST /api/orders/limit` 응답과 동일 타입 재사용) — `orderId`·`requestedAt`은 변경 전 값 그대로 유지(새 주문을 만들지 않고 같은 행을 갱신하므로 `GET /api/orders/pending`(LMT-004)의 정렬 위치가 바뀌지 않는다), `status`는 `PENDING` 유지 | `limitPrice`·`quantity` 둘 다 생략("변경할 값이 없습니다."), 합성된 최종 수량이 0 이하이거나 코인 8자리 초과, 합성된 최종 지정가가 0 이하, 합성된 최종값 기준 코인 최소주문금액(5,000원) 미달은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `orderId`에 해당하는 주문 없음은 404 `NOT_FOUND`. 존재하지만 요청자 소유가 아니면 403 `FORBIDDEN`. 본인 소유이지만 이미 `FILLED`면 409 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED`면 409 `ORDER_ALREADY_CANCELLED`(둘 다 LMT-003과 동일 코드 재사용, 신규 오류 코드 없음). 예약 가능 현금(`cashBalance-reservedCash`, 변경 전 예약 해제 후 기준) 부족(BUY)은 409 `INSUFFICIENT_CASH`(047 이후 샌드박스 종목이면 튜토리얼 계좌 기준으로 판정해 409 `TUTORIAL_INSUFFICIENT_CASH`), 예약 가능 수량(`quantity-reservedQuantity`, 변경 전 예약 해제 후 기준) 부족(SELL)은 409 `INSUFFICIENT_QTY`(둘 다 LMT-001 생성과 동일 코드 재사용) | 015 LMT-005, Issue #239 |

교육 세션 귀속 주문(`practicePriceSessionId` non-null)도 필터 없이 동일하게 조회·수정 가능하다(`findByIdForUpdate`가 `practicePriceSessionId`로 구분하지 않음, 030 이슈 #320 사전 분석에서 예견된 의도된 동작).

이 엔드포인트는 신규 오류 코드가 전혀 없다 — `VALIDATION_ERROR`·`NOT_FOUND`·`FORBIDDEN`·`ORDER_ALREADY_FILLED`·`ORDER_ALREADY_CANCELLED`·`INSUFFICIENT_CASH`·`INSUFFICIENT_QTY` 전부 LMT-001·LMT-003이 이미 쓰던 코드를 그대로 재사용한다. 검증 순서·잠금 순서는 LMT-003(취소)과 동일하게 존재(404)→소유(403)→상태(409)→(형식·최소주문금액 재검증)→`order → account → (SELL만) holding` 락 순서로 고정된다(`LimitOrderModifyService.modifyOrder`). 변경 흐름은 "해제 후 재예약"이다 — 매수는 `Account.releaseReservedCash(변경 전 예약)` → 예약 가능 현금 재검증 → `Account.reserveCash(변경 후 예약)`, 매도는 `Holding.releaseReservedQuantity(변경 전 수량)` → 예약 가능 수량 재검증 → `Holding.reserveQuantity(변경 후 수량)` 순으로 처리하며, 재예약 단계에서 거부되면(`INSUFFICIENT_CASH`/`INSUFFICIENT_QTY`) 트랜잭션 전체가 롤백되어 해제도 함께 취소되므로 주문·계좌·보유 모두 변경 전 상태 그대로 남는다(부분 반영 없음). 예약 재계산 비용(수수료 포함 `수량 × 지정가` 총 예약액)은 `LimitOrderFeeCalculator`(LMT-001 생성·LMT-003 취소와 공통)로 계산해 변경 전·후 값을 각각 산출한다. 수정 결과가 즉시 체결 조건(매수 지정가≥현재가, 매도 지정가≤현재가)을 충족해도 LMT-001 생성과 동일하게 거부하지 않는다 — 체결은 다음 가격 갱신 트리거(LMT-002)에서만 발생한다. 체결 트리거(LMT-002)·취소(LMT-003)와 수정이 동시에 도착해도 세 흐름 모두 order를 가장 먼저 잠그므로 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 갱신된 `status`를 보고 자기 작업을 거부/no-op한다.

### 내 주문 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/orders | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{requestedAt}_{id}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"limitPrice":null,"requestedAt":"2026-07-29T09:00:00","practiceAttemptId":null,"practiceAttemptRunNumber":null}, ...],"nextCursor":"2026-07-29T09:00:00_1","hasNext":true}` (`OrderListResponse`); 주문이 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor` 파싱 실패는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` | 006 PORT-003, 018 PORT-003(1차 고도화), Issue #21, Issue #182; 015 LMT-004, Issue #235; 039 TUTORIAL-FLOW-003·007, Issue #378 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌 주문으로만 결정한다. 응답 항목은 기존 9필드에 nullable `practiceAttemptId`·`practiceAttemptRunNumber`를 추가한 11필드다. 두 귀속 필드는 attempt 주문이면 함께 non-null이고 일반·legacy 주문이면 함께 null이며, 현재 run pending 복원 시 둘을 모두 일치시켜야 한다. 체결 전용 필드(`tradeId`·`price`·`amount`·`fee`·`executedAt`)는 포함하지 않는다.

### 미체결 주문 목록 조회 (LMT-004)

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/orders/pending | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO`), `cursor`·`limit` 선택 | 200 `{"content":[{"orderId":3,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":70000000,"requestedAt":"2026-08-06T09:00:00","practiceAttemptId":7,"practiceAttemptRunNumber":2}],"nextCursor":null,"hasNext":false}` (`OrderListResponse`, 목록과 동일 11필드) | 조회 공통 400·401·404 오류 | 015 LMT-004, Issue #235; 039 TUTORIAL-FLOW-003·007, Issue #378 |

조회 대상·정렬·커서·11필드는 `GET /api/orders`와 동일하고 `status=PENDING`만 필터링한다. attempt ID/run을 함께 비교하면 같은 종목의 일반 주문, 재시작 전 stale run 주문을 현재 튜토리얼 UI가 채택·취소하지 않는다.

### 내 체결 내역 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/trades | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{executedAt}_{id}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"tradeId":2,"instrumentId":1,"side":"SELL","price":71000,"quantity":5,"amount":355000,"fee":53,"realizedPnl":5000,"executedAt":"2026-07-29T09:05:00"},{"tradeId":1,"instrumentId":1,"side":"BUY","price":70000,"quantity":10,"amount":700000,"fee":105,"realizedPnl":null,"executedAt":"2026-07-29T09:00:00"}],"nextCursor":"2026-07-29T09:00:00_1","hasNext":true}` (`TradeListResponse`); 체결내역이 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor`가 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패(구분자 없음·날짜 파싱 실패·id 파싱 실패)는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-002, Issue #82 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌 체결내역으로만 결정한다(`AccountService.getAccountFor`로 소유권+시장 스코프 검증). `executedAt` 내림차순, 동시각은 `id` 내림차순으로 정렬하며, 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 id보다 작은)" 조건으로 다음 페이지를 이어받아 페이지 경계에서 중복·누락이 없다. 응답 항목 필드는 `tradeId`·`instrumentId`·`side`·`price`·`quantity`·`amount`·`fee`·`realizedPnl`·`executedAt` 9개로 고정이며, 매수 건은 `realizedPnl`이 항상 `null`이고 매도 건만 FIFO 실현손익 값을 갖는다. `orderId`·`orderType`·`status`·`requestedAt` 등 주문 목록(`GET /api/orders`) 전용 필드는 포함하지 않는다 — 두 API는 "무엇을 요청했는가"와 "실제로 얼마에 체결됐는가"를 분리해서 보여준다.

## 021 일반 리스크관리 OCO — 생성·목록·취소 (production, `intentionId` 생략 일반 경로만)

`docs/specs/021-general-risk-management-oco`(plan.md "API 설계"·"일반 경로 검증 순서"·"응답 계약")가 정본이다. Issue #348로 `ExitPlanController`(`POST /api/exit-plans`, `DELETE /api/exit-plans/{exitPlanId}`)가 먼저 production에 추가됐고, 후속 "목록·응답 계약 전환" 작업으로 `GET /api/exit-plans?status=`도 production이 됐다 — **셋 다 `intentionId`를 생략하는 일반 경로만이다.** 가격 트리거·자동 청산은 이미 production(`ExitPlanTriggerListener`/`ExitPlanFillService`)이며, `intentionId`를 지정하는 교육 경로 재접합만 아직 미착수다(`docs/api/education.md`의 "016 투자 실습" 절 참고).

`POST`·`DELETE`는 두 경로가 라우트를 공유한다 — `intentionId`가 non-null이면 컨트롤러가 아니라 서비스 계층(`ExitPlanService.rejectUnsupportedEducationalPath`)이 무조건 400 `VALIDATION_ERROR`("intentionId를 지정하는 교육 경로는 아직 지원하지 않습니다.")로 거부한다. `GET`은 `intentionId`를 아예 받지 않으므로(쿼리 파라미터가 아니다) 이 거부 분기와 무관하게 본인 소유 plan을 경로 구분 없이 그대로 반환한다 — 교육 경로가 재접합되면 그 plan도 같은 목록에 함께 나타난다.

### OCO 예약 생성 (일반 경로)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/exit-plans | 필수 `Idempotency-Key: <UUID>`(공백 불가, 최대 36자); body `{"holdingId":1,"quantity":10,"exitPriceType":"PRICE","stopLoss":65000,"takeProfit":75000}` 또는 `exitPriceType":"PERCENT"`면 `stopLossRate`·`takeProfitRate` (`ExitPlanCreateRequest`) | 최초 201, 같은 key 재요청 수렴 200 `ExitPlanResponse` | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(holding 없음/타인 소유); 409 `EXIT_PLAN_ALREADY_EXISTS`, `EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`, `INSUFFICIENT_QTY`, `EXIT_PLAN_INVALID_PRICE_RANGE`, `PRICE_UNAVAILABLE`, `IDEMPOTENCY_CONFLICT` | 021, Issue #348, Issue #461 |

**요청 필드 (일반 경로 필수·금지, plan.md 필드 표)**

- `holdingId`(양의 `Long`)·`quantity`(양수 `BigDecimal`)·`exitPriceType`(`PRICE`\|`PERCENT`)는 **필수**다. 셋 중 하나라도 없으면 400 `VALIDATION_ERROR`.
- `intentionId`·`buyTradeId`·`instrumentId`는 일반 경로에 **존재하지 않는다** — 요청 body에 포함되면(교육 경로 필드 포함) 400 `VALIDATION_ERROR`로 거부한다. 이 PR 시점에는 `intentionId`를 지정하면 (교육 경로가 아직 구현되지 않았으므로) 항상 400이다.
- `exitPriceType=PRICE`면 `stopLoss`·`takeProfit`이 둘 다 필수이고 `stopLossRate`·`takeProfitRate`는 금지(포함 시 400). `PERCENT`면 반대다.
- `holding`이 존재하지 않거나 본인 소유가 아니면 404 `NOT_FOUND`(소유 여부 비노출).
- `holding.instrument.market != CRYPTO`(주식 holding)면 400 `VALIDATION_ERROR`("코인 종목만 일반 리스크관리 OCO를 지원합니다.") — 이 spec 범위는 코인 전용이다.
- `holding.instrument.isTutorialSample()`(투자 실습 튜토리얼 전용 샌드박스 종목)이면 409 `EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`("샌드박스 종목은 일반 리스크관리 OCO를 지원하지 않습니다.") — `intentionId` 재접합 전까지 샌드박스 holding은 일반 경로 대상이 아니다(Issue #461, RISK-OCO-014).
- 같은 holding에 이미 `PENDING` exit plan이 있으면(멱등 재현이 아닌 한) 409 `EXIT_PLAN_ALREADY_EXISTS`(이 판정이 아래 수량 판정보다 먼저다 — 기존 예약이 원인인데 `INSUFFICIENT_QTY`로 가려지지 않게).
- `holding.getAvailableQuantity() < quantity`면 409 `INSUFFICIENT_QTY`.
- 계산된 `stopLossPrice`·`takeProfitPrice`가 `0 < stopLossPrice < entryPrice < takeProfitPrice` 또는 `DECIMAL(18,8)` 상한을 벗어나면 409 `EXIT_PLAN_INVALID_PRICE_RANGE`.
- 서버 유효 현재가가 없으면 409 `PRICE_UNAVAILABLE`(이 단계까지 도달하지 않으면 plan·condition·예약 흔적이 남지 않는다).
- `Idempotency-Key` 헤더는 필수다. 같은 key로 같은 요청 본문(정규화된 fingerprint 일치)을 재현하면 200으로 과거 plan을 반환하고, 같은 key에 다른 본문이면 409 `IDEMPOTENCY_CONFLICT`.
- `entryPrice`는 생성 시점 `holding.averagePrice` snapshot이다. 응답의 `intentionId`·`buyTradeId`는 일반 경로에서 항상 null이고 `holdingId`는 항상 non-null이다.

### OCO 예약 취소 (경로 공통)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/exit-plans/{exitPlanId} | 양의 `exitPlanId` path | 204, 본문 없음 | 404 `EXIT_PLAN_NOT_FOUND`; 409 `EXIT_PLAN_NOT_PENDING`, **(042, Issue #477)** `EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`(튜토리얼 자동 예약은 이 경로로 취소할 수 없다 — 042가 tick 정산·재시작·매도 접수에서 관리하는 생명주기를 밖에서 깨뜨리기 때문이다. 튜토리얼 내부 취소는 이 호출부를 거치지 않는다) | 021, Issue #348; 042 EXITPRESET-016, Issue #477 |

본인 소유의 `PENDING` exit plan만 취소한다. 존재하지 않거나 타인 소유는 존재를 숨겨 404 `EXIT_PLAN_NOT_FOUND`다(소유 여부 비노출). `PENDING`이 아닌(이미 체결·취소된 terminal) plan을 취소하려 하면 409 `EXIT_PLAN_NOT_PENDING`이다. 검증 순서는 항상 존재 → 상태다. 성공 시 `holding.releaseReservedQuantity(plan.getQuantity())`로 예약 수량을 정확히 한 번 반환하고 대기 중인 `exit_plan_conditions`도 함께 취소한다. 잠금 순서는 `holding → plan`(트리거와 동일해 데드락이 없다).

### OCO 예약 목록 조회 (production, 경로 무관)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/exit-plans?status= | 선택 query `status`(`ExitPlanStatus` 리터럴: `PENDING`\|`FILLED_TAKE_PROFIT`\|`FILLED_STOP_LOSS`\|`CANCELLED`); 생략 시 `PENDING` | 200 `ExitPlanListResponse`(`{"content":[ExitPlanResponse...]}`); 대상 없으면 `content:[]`. **(042, Issue #477) 튜토리얼 자동 예약(샌드박스 종목)은 제외한다** — 그 예약이 걸린 holding을 `GET /api/holdings`가 033 SANDBOX-EXCL-001로 이미 감추므로, 함께 걸러 내지 않으면 실거래 화면에 대응 보유가 없는 유령 예약이 뜬다 | 400 `VALIDATION_ERROR`(허용 값 밖 리터럴) | 021, Issue #348; 042 EXITPRESET-016, Issue #477 |

`ExitPlanService.list`가 `ExitPlanRepository.findByUserIdAndStatusOrderByIdDesc(userId, status)`로 인증 사용자 본인 소유 plan만 `id` 내림차순 조회한다(페이지네이션 없음). `status`는 controller가 `@RequestParam(required = false) ExitPlanStatus`로 바인딩하며, `ExitPlanStatus` 리터럴이 아닌 값은 Spring 타입 변환 실패로 전역 예외 핸들러가 400 `VALIDATION_ERROR`로 응답한다(다른 도메인의 잘못된 enum 쿼리 파라미터와 동일한 처리 — 신규 오류 코드 없음). 응답 항목은 생성·취소가 이미 쓰는 `ExitPlanResponse`를 그대로 재사용하므로 `holdingId`는 항상 non-null, `intentionId`·`buyTradeId`는 일반 경로 plan에서 항상 null이다(교육 경로 plan이 이후 함께 나타나면 그때 non-null이 될 수 있다).

이 절이 참조하는 `EXIT_PLAN_ALREADY_EXISTS`(409)·`EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`(409)·`EXIT_PLAN_INVALID_PRICE_RANGE`(409)·`EXIT_PLAN_NOT_FOUND`(404)·`EXIT_PLAN_NOT_PENDING`(409)은 모두 `ErrorCode` enum에 이미 존재한다.
