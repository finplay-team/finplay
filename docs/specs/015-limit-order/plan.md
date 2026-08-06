# Plan: 코인 지정가 매매 — 생성·체결 (LMT-001~002)

## 관련 문서

- Spec: `./spec.md`
- 참고 spec: `docs/specs/011-order-ledger-schema/plan.md`(기존 `orders`/`trades`/`holdings` 스키마 설계 근거), `docs/specs/004-order-buy`·`005-order-sell`(시장가 검증·체결 로직 원본), `docs/specs/016-investment-education-policy/plan.md`(비관적 락 다단계 순서 선례 — `progress → favorite → intention → plan`)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 패키지 구조, cross-domain repository 직접 주입 금지), [ADR-0003](../../adr/0003-testing-strategy.md)(테스트 피라미드, Testcontainers 통합 테스트), [ADR-0004](../../adr/0004-flyway-migrations.md)(Flyway로만 스키마 변경)
- PRD 근거: `docs/prd.md` 591~615행(LMT-001·LMT-002), §3 구현 현황 211행

## 기존 코드 현황 (구현 전 확인한 사실)

- `OrderExecutionService`(시장가 매수·매도)는 **현재 어떤 비관적 락도 걸지 않는다.** `AccountRepository.findByUserIdAndMarket`·`HoldingRepository.findByAccountIdAndInstrumentId`는 모두 plain read이고 `@Lock`이 없다. 유일한 동시성 방어는 `orders` 테이블 `UNIQUE(user_id, idempotency_key)` 위반을 잡아 재조회하는 것뿐이다. 이번 PR이 주문 원장에 비관적 락을 **처음** 도입한다.
- 비관적 락 패턴 선례는 `PracticeProgressRepository.findByUserIdAndTutorialKeyForUpdate`(`@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query`)에 있다. 이 패턴을 그대로 재사용한다.
- `PriceStore.saveTick(symbol, price, receivedAt)`(`com.finplay.api.market.store.PriceStore`)이 코인 가격 갱신의 유일한 반영 지점이다. `BithumbWebSocketFeedClient`·`FakeBithumbFeedClient`에서 호출된다. 이미 "과거 틱이면 무시"하는 로직이 있다(MKT-003) — 이번에 이 메서드가 실제로 최신값을 갱신했을 때만 이벤트를 publish하도록 확장한다.
- `InstrumentRepository`에는 symbol로 조회하는 메서드가 없다 — 신규 추가.
- `OrderType`은 `MARKET`만, `OrderStatus`는 `FILLED`만 존재한다 — 각각 `LIMIT`·`PENDING` 추가.
- `Order` 엔티티에는 지정가를 담을 필드가 없다 — `limitPrice`(nullable) 추가.
- `Holding`에는 `reservedQuantity`, `Account`에는 `reservedCash`에 해당하는 컬럼이 없다 — 신규 컬럼.

## API 설계

### `POST /api/orders/limit`

- 인증 필요. `Idempotency-Key` 헤더 필수(기존 `POST /api/orders`와 동일 패턴 — `OrderService`의 재조회·409 로직을 그대로 재사용).
- 응답 201, `LimitOrderResponse`(체결 결과가 없으므로 기존 `OrderResponse`와 다른 별도 DTO).

**요청 (`LimitOrderCreateRequest`)**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| market | `Market`(enum) | Y | `CRYPTO`만 허용, `STOCK`이면 400 `VALIDATION_ERROR`("코인 종목만 지정가 주문을 지원합니다.") |
| instrumentId | Long | Y | 존재하지 않으면 404 `NOT_FOUND`, `instrument.market != market`이면 400 `VALIDATION_ERROR` |
| side | `OrderSide`(enum, BUY/SELL) | Y | |
| quantity | BigDecimal | Y | 0보다 커야 함, 소수점 8자리 이하(기존 ORD-003 코인 규칙 재사용) |
| limitPrice | BigDecimal | Y | 0보다 커야 함 |

- 최소주문금액(코인 5,000원) 검증은 `quantity × limitPrice`(내림 전 금액)로 기존 `validateMinOrderAmount`와 동일 로직을 재사용한다.

**응답 (`LimitOrderResponse`)**

| 필드 | 설명 |
|---|---|
| orderId | |
| market | |
| instrumentId | |
| side | |
| orderType | 항상 `"LIMIT"` |
| status | 항상 `"PENDING"`(생성 시점에 즉시체결 조건이어도 거부하지 않으므로 생성 응답은 항상 PENDING) |
| quantity | |
| limitPrice | |
| requestedAt | |

**오류 코드**

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | VALIDATION_ERROR | market≠CRYPTO, 수량·지정가 형식/범위 위반, 최소주문금액 미달, 다른 시장 종목 조합 |
| 404 | NOT_FOUND | instrumentId 없음, 계좌 없음 |
| 409 | INSUFFICIENT_CASH | 매수 예약 현금 부족(`availableCash` 기준) |
| 409 | INSUFFICIENT_QTY | 매도 예약 가능 수량 부족(`availableQuantity` 기준) |
| 409 | IDEMPOTENCY_CONFLICT | 동일 키·다른 본문, 또는 경합 재현 실패 |

새 `ErrorCode`는 추가하지 않는다 — 전부 기존 코드 재사용.

## 데이터 모델

### `V22__add_limit_order_reservation_ledger.sql`

```sql
ALTER TABLE accounts
    ADD COLUMN reserved_cash BIGINT NOT NULL DEFAULT 0 AFTER cash_balance;

ALTER TABLE holdings
    ADD COLUMN reserved_quantity DECIMAL(30,8) NOT NULL DEFAULT 0 AFTER quantity;

ALTER TABLE orders
    ADD COLUMN limit_price DECIMAL(18,8) NULL AFTER quantity;

ALTER TABLE orders
    ADD INDEX idx_orders_limit_fill (instrument_id, status, side, limit_price);
```

- `reserved_cash`·`reserved_quantity`는 기존 `cash_balance`·`quantity`와 정밀도를 맞춘다(각각 `BIGINT`, `DECIMAL(30,8)`).
- `limit_price`는 기존 가격류 컬럼과 동일하게 `DECIMAL(18,8)`(코인 tick 0.1 표현 가능).
- `orders.order_type`·`orders.status`는 이미 `VARCHAR(10)`이라 `"LIMIT"`(5자)·`"PENDING"`(7자) 모두 컬럼 변경 없이 저장 가능하다.
- `idx_orders_limit_fill`은 LMT-002 체결 후보 조회(`instrument_id`+`status`+`side`+`limit_price` 조건)의 풀스캔을 막는다. 기존 `docs/specs/011-order-ledger-schema/plan.md`와 마�찬가지로 CHECK 제약은 쓰지 않는다(코드베이스 전례 없음 — 앱 계층에서 불변식 검증).
- 기존 `uk_trades_order UNIQUE(order_id)` 제약은 그대로 유지한다 — 지정가도 전량 목표가 체결만 지원해(부분체결 제외 범위) 주문 1건당 체결 1건 불변식이 여전히 성립한다.

### 엔티티 변경

**`OrderType`**: `LIMIT` 추가.
**`OrderStatus`**: `PENDING` 추가.

**`Order`**: `limitPrice`(nullable `BigDecimal`, precision 18 scale 8) 필드 추가. 기존 private 생성자에 초기 `status`·`limitPrice` 파라미터를 추가하되, 공개 팩토리 `Order.create(...)`(기존 시그니처 그대로, 내부에서 `status=FILLED`, `limitPrice=null` 고정)는 호출부 변경 없이 그대로 동작해야 한다. 신규 팩토리:

```java
public static Order createLimitPending(
    User user, Account account, Instrument instrument, OrderSide side,
    BigDecimal quantity, BigDecimal limitPrice,
    String idempotencyKey, String requestHash, LocalDateTime requestedAt) { ... }
// orderType=LIMIT, status=PENDING로 생성
```

체결 확정 메서드:

```java
public void markFilled() {
    if (this.status != OrderStatus.PENDING) {
        throw new IllegalStateException("PENDING 상태의 주문만 체결 확정할 수 있습니다.");
    }
    this.status = OrderStatus.FILLED;
}
```

**`Account`**: `reservedCash`(`long`, 기본 0) 필드 추가.

```java
public long getAvailableCash() { return cashBalance - reservedCash; }

public void reserveCash(long amount) {
    if (amount > getAvailableCash()) {
        throw new IllegalStateException("예약 가능한 현금보다 큰 금액을 예약할 수 없습니다.");
    }
    this.reservedCash += amount;
}

// 체결 확정: 예약 → 실제 지출로 전환 (reservedCash 감소 + cashBalance 감소를 원자적으로 한 메서드에)
public void confirmReservedCash(long amount) {
    if (amount > this.reservedCash) {
        throw new IllegalStateException("예약된 금액보다 큰 금액을 확정할 수 없습니다.");
    }
    this.reservedCash -= amount;
    this.cashBalance -= amount;
}
```

`releaseReservedCash`(취소용)는 이번 PR에서 쓰이지 않으므로 추가하지 않는다 — LMT-003에서 추가한다. 서비스 계층은 기존 `createBuyOrder`와 동일하게 **entity 메서드 호출 전에** `availableCash < cashRequired`를 확인해 `BusinessException(INSUFFICIENT_CASH)`를 던진다(entity의 `IllegalStateException`은 방어적 이중 체크).

**`Holding`**: `reservedQuantity`(`BigDecimal`, 기본 `ZERO`) 필드 추가.

```java
public BigDecimal getAvailableQuantity() { return quantity.subtract(reservedQuantity); }

public void reserveQuantity(BigDecimal qty) {
    if (qty.compareTo(getAvailableQuantity()) > 0) {
        throw new IllegalStateException("예약 가능한 수량보다 큰 수량을 예약할 수 없습니다.");
    }
    this.reservedQuantity = this.reservedQuantity.add(qty);
}

// 체결 확정: 예약 해제만 담당. 실제 수량 차감은 기존 applySell을 그대로 재사용한다(시장가 매도와 공유).
public void releaseReservedQuantity(BigDecimal qty) {
    if (qty.compareTo(this.reservedQuantity) > 0) {
        throw new IllegalStateException("예약된 수량보다 큰 수량을 해제할 수 없습니다.");
    }
    this.reservedQuantity = this.reservedQuantity.subtract(qty);
}
```

`applySell`은 건드리지 않는다 — 시장가 매도는 예약이 없으므로(`reservedQuantity`가 항상 0) 그대로 호출해도 안전하다. 지정가 체결은 `releaseReservedQuantity` + `applySell`을 함께 호출한다(순서 무관, 같은 트랜잭션 내 커밋 전).

## 패키지·클래스 설계

기존 시장가 코드(`OrderService`/`OrderExecutionService`)는 SELL 락 순서 조정(아래 §5) 외에는 건드리지 않는다 — 회귀 위험을 그 조정 지점으로 국한하기 위해 지정가는 **병렬 클래스**로 둔다(011-order-ledger-schema plan.md가 확립한 "Service=오케스트레이션/멱등성, ExecutionService=비즈니스 로직" 분리를 재사용).

| 클래스 | 패키지 | 역할 |
|---|---|---|
| `LimitOrderCreateRequest` | `order.dto.request` | 요청 DTO |
| `LimitOrderResponse` | `order.dto.response` | 응답 DTO |
| `LimitOrderService` | `order.service` | 멱등성 판정 후 `LimitOrderCreationService`에 위임(기존 `OrderService.createOrder`와 동일 패턴 — 선제 조회 → 실행 → `DataIntegrityViolationException` 캐치 → 재조회 폴백) |
| `LimitOrderCreationService` | `order.service` | 검증·예약·`PENDING` 저장 (아래 §4 흐름) |
| `CryptoPriceUpdatedEvent` | `market.event` | `record(String symbol, BigDecimal price, LocalDateTime receivedAt)` |
| `LimitOrderTriggerListener` | `order.listener` | `@EventListener`(비트랜잭션) — 후보 주문 조회 후 1건씩 위임 |
| `LimitOrderFillService` | `order.service` | 주문 1건 체결(별도 트랜잭션), 락 획득·체결·`markFilled` |

`OrderController`(기존 파일)에 `@PostMapping("/limit")` 메서드를 추가한다 — 같은 `/api/orders` 리소스의 하위 경로라 새 컨트롤러를 만들지 않는다.

### Repository 추가 메서드

```java
// AccountRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.user.id = :userId and a.market = :market")
Optional<Account> findByUserIdAndMarketForUpdate(@Param("userId") Long userId, @Param("market") Market market);

@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Account> findByIdForUpdate(Long id); // JpaRepository 파생 + @Lock, 또는 @Query로 명시

// HoldingRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select h from Holding h where h.account.id = :accountId and h.instrument.id = :instrumentId")
Optional<Holding> findByAccountIdAndInstrumentIdForUpdate(@Param("accountId") Long accountId, @Param("instrumentId") Long instrumentId);

// OrderRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Order> findByIdForUpdate(Long id);

@Query("""
    select o from Order o
    where o.instrument.id = :instrumentId and o.status = com.finplay.api.order.domain.OrderStatus.PENDING
      and o.orderType = com.finplay.api.order.domain.OrderType.LIMIT
      and ((o.side = com.finplay.api.order.domain.OrderSide.BUY and o.limitPrice >= :price)
        or (o.side = com.finplay.api.order.domain.OrderSide.SELL and o.limitPrice <= :price))
    order by o.requestedAt asc, o.id asc
    """)
List<Order> findPendingLimitOrdersToFill(@Param("instrumentId") Long instrumentId, @Param("price") BigDecimal price);

// InstrumentRepository
Optional<Instrument> findByMarketAndSymbol(Market market, String symbol);
```

`AccountService`·`PortfolioSellService`에 각각 대응하는 `getAccountForUpdate(userId, market)`/`getAccountByIdForUpdate(accountId)`, `getHoldingForUpdateOrThrow(account, instrument, requiredQuantity)`(내부에서 `availableQuantity` 기준으로 검증)를 추가해, 다른 도메인 서비스가 repository를 직접 주입하지 않도록 한다(ADR-0002).

## 지정가 생성 흐름 (LMT-001)

공통: `Instrument` 조회·시장 일치 검증(`market == CRYPTO` 포함)·수량 형식 검증은 기존 `OrderExecutionService`의 사설 메서드와 동일 로직을 `LimitOrderCreationService`에 복제한다(패키지 사설 메서드라 재사용 불가 — 011 plan.md 전례처럼 규모가 작아 추상화보다 복제가 낫다고 판단. 리뷰에서 중복이 실제 문제가 되면 공용 `OrderValidationSupport`로 추출은 후속 리팩터링).

**BUY**

1. `accountService.getAccountForUpdate(userId, market)` — 계좌 락(신규 락, 시장가 매수는 이 락을 타지 않으므로 서로 겹치지 않음 — "알려진 한계" 참고).
2. `cashRequired = FLOOR(quantity × limitPrice) + FLOOR(FLOOR(quantity × limitPrice) × CRYPTO_FEE_RATE)`.
3. `account.getAvailableCash() < cashRequired`면 `BusinessException(INSUFFICIENT_CASH)`.
4. `account.reserveCash(cashRequired)`.
5. `Order.createLimitPending(...)` 저장(트레이드 없음).

**SELL**

1. `accountService.getAccountFor(userId, market)` — **락 없음**(현금을 건드리지 않으므로 단일 락(holding)만 필요, 계좌를 잠글 이유가 없다 — 두 자원을 동시에 들고 있지 않으므로 ABBA 위험도 없다).
2. `holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)` — holding 락 + `availableQuantity` 검증(부족 시 `INSUFFICIENT_QTY`).
3. `holding.reserveQuantity(quantity)`.
4. `Order.createLimitPending(...)` 저장.

## 가격 갱신 이벤트 (LMT-002 트리거)

- `PriceStore.saveTick`에 `ApplicationEventPublisher`를 주입하고, 기존 "과거 틱이면 무시하고 return" 분기를 통과해 실제로 Redis 값을 갱신한 직후에만 `eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt))`를 호출한다.
- 일반 `@EventListener`(트랜잭션 경계 밖, `AFTER_COMMIT` 아님) — 가격 수신 자체가 DB 트랜잭션이 아니므로 커밋 대기 대상이 없다(PRD 614행 근거).

## 체결 리스너·체결 서비스 (LMT-002)

`LimitOrderTriggerListener.onPriceUpdated(CryptoPriceUpdatedEvent event)`:

1. `instrumentRepository.findByMarketAndSymbol(CRYPTO, event.symbol())` — 없으면 로그만 남기고 종료(코인 목록에 없는 심볼일 수 있음, MKT-003과 동일하게 관용적으로 처리).
2. `orderRepository.findPendingLimitOrdersToFill(instrumentId, event.price())`로 후보를 **단일 쿼리**로 가져온다(N+1 방지 — 전체 PENDING을 스캔하지 않고 해당 종목·조건에 인덱스로 바로 히트).
3. 후보를 `requestedAt` 오름차순(쿼리가 이미 정렬)으로 순회하며 `limitOrderFillService.fillIfPending(order.getId())`를 **1건씩 호출**한다. 각 호출은 독립된 새 트랜잭션(호출부인 리스너 메서드 자체는 트랜잭션이 아니므로 각 서비스 호출이 자기 트랜잭션을 새로 연다) — 한 건이 실패해도 나머지 처리에 영향 없음.
4. 리스너 메서드 전체를 try/catch로 감싸 어떤 예외도 빗썸 피드 수신 스레드로 전파하지 않는다(`RankingEventListener`와 동일 관례, 건별로도 개별 catch).

`LimitOrderFillService.fillIfPending(Long orderId)`(`@Transactional`):

1. `order = orderRepository.findByIdForUpdate(orderId).orElseThrow(...)` — **주문 락**.
2. `order.getStatus() != PENDING`이면 즉시 반환(중복 이벤트 도착 시 no-op — 완료조건 "동시 체결 경합" 방어).
3. **`account = accountService.getAccountByIdForUpdate(order.getAccount().getId())`** — 계좌 락(먼저).
4. `order.getSide() == SELL`이면 **`holding = holdingRepository.findByAccountIdAndInstrumentIdForUpdate(...)`** — holding 락(다음). `order.getSide() == BUY`면 holding 조회는 락 없이 `existsBy...` 또는 조회 시도만 하고, 없으면 이후 `portfolioBuyService`가 신규 INSERT를 수행한다(락 대상이 아직 없으므로 "holding 없으면 account → order" 규칙 그대로).
5. `amount = FLOOR(quantity × limitPrice)`, `fee = FLOOR(amount × CRYPTO_FEE_RATE)`(생성 시 예약과 동일 계산 — 재계산이지만 값은 항상 같다).
6. **BUY**: `account.confirmReservedCash(amount + fee)` → `Trade.of(...)` 저장(`price=limitPrice`) → `portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, limitPrice, fee, now)`(기존 시장가 매수와 동일한 lot 생성 로직 재사용, holding 신규 생성 포함) → `order.markFilled()`.
7. **SELL**: `holding.releaseReservedQuantity(quantity)` → `Trade.of(...)` 저장 → `allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now)`(기존 FIFO lot 소비 재사용) → `realizedPnl` 계산(기존 시장가 매도와 동일 공식) → `trade.fillRealizedPnl(...)` → `account.addCash(amount - fee)` → `account.addRealizedPnl(realizedPnl)` → `order.markFilled()` → `eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()))`(기존 랭킹 갱신 훅 그대로 재사용 — `RankingEventListener`가 `AFTER_COMMIT`에 반응하므로 이 트랜잭션이 커밋된 뒤 랭킹이 갱신된다).

**잠금 순서 요약**

| 흐름 | 순서 |
|---|---|
| LMT-001 BUY 생성 | account |
| LMT-001 SELL 생성 | holding |
| LMT-002 BUY 체결 | order → account → (holding 없으면 skip) |
| LMT-002 SELL 체결 | order → account → holding |
| 시장가 SELL 체결(조정 후) | account → holding |

순서 표기는 "먼저 잠그는 것부터"다. LMT-002는 1단계에서 order를 먼저 잠그는데, 이는 "같은 주문에 대한 중복 이벤트"를 막는 목적의 락이라 계좌·holding과는 경쟁 대상이 다르다 — 실제로 여러 트랜잭션이 동시에 경합하는 자원은 **account와 holding**이고, 이 둘의 순서는 항상 account 먼저다. 어떤 흐름도 holding을 account보다 먼저 잠그지 않으므로 ABBA 사이클이 없다.

## 기존 시장가 매도 락 순서 조정 (완료조건 필수 항목)

**변경 전** (`OrderExecutionService`):

```java
// execute()
Account account = getAccountFor(userId, request.market()); // 락 없음, BUY/SELL 공통
...
// createSellOrder(...)
Holding holding = portfolioSellService.getHoldingOrThrow(account, instrument, quantity); // 락 없음, holding 먼저 read
```

**변경 후**:

- `execute()`에서 계좌를 미리 fetch하지 않는다. `createBuyOrder`/`createSellOrder`가 각자 계좌를 가져온다.
- `createBuyOrder`: 기존과 동일하게 `accountService.getAccountFor(userId, market)`(락 없음) — **이번 PR에서 변경하지 않는다**(spec.md "알려진 한계").
- `createSellOrder`: `account = accountService.getAccountForUpdate(userId, market)`(**신규 락**, holding보다 먼저) → `holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)`(**신규 락**, `availableQuantity` 기준 — 예약된 수량 반영). 이후 가격 조회·`Order`/`Trade` 생성·`applySellTrade`·`account.addCash`/`addRealizedPnl`은 그대로 두되, 전부 이미 획득한 락 안에서 실행된다.
- `PortfolioSellService.getHoldingOrThrow`(락 없음, `quantity` 기준)는 더 이상 시장가 매도에서 호출하지 않는다. 완전히 제거할지 다른 호출부가 남는지는 구현 시 확인한다(현재 코드베이스에서 유일한 호출부이므로 제거 가능성이 높다). 대체하는 `getHoldingForUpdateOrThrow`는 `availableQuantity` 기준으로 검증한다.

이 조정으로 시장가 매도와 LMT-002 SELL 체결이 동시에 서로 다른 주문(그러나 겹칠 수 있는 계좌·holding)에 대해 실행돼도 항상 account → holding 순서로 락을 시도하므로 데드락이 발생하지 않는다.

## 동시성 테스트 시나리오 (`@SpringBootTest` + Testcontainers, ADR-0003)

1. **중복 체결 방지**: 같은 `PENDING` 지정가 주문에 대해 `limitOrderFillService.fillIfPending(orderId)`를 두 스레드에서 거의 동시에 호출한다 → 정확히 1건의 `Trade`만 생성되고, `Order.status`는 `FILLED` 1회만 전이됨을 검증(하나는 락 대기 후 `status != PENDING`을 보고 no-op).
2. **ABBA 데드락 회귀**: 스레드 A는 지정가 SELL 체결(`LimitOrderFillService`, order→account→holding), 스레드 B는 시장가 SELL(`OrderExecutionService`, account→holding, 서로 다른 주문이지만 같은 계좌·holding row를 대상으로) 실행 — 둘 다 타임아웃 없이 완료됨을 검증(데드락이면 MySQL이 `Deadlock found` 예외를 던지거나 락 대기 타임아웃으로 실패).
3. **매수 생성 시 현금 예약**: `POST /api/orders/limit` BUY 성공 후 `accounts.reserved_cash`가 정확히 증가하고 `cashBalance`는 불변임을 DB로 검증. 예약 가능 현금을 초과하는 두 번째 요청은 409.
4. **매도 생성 시 이중예약 방지**: 같은 holding에 대해 지정가 SELL을 예약한 뒤, 남은 `availableQuantity`를 초과하는 두 번째 지정가 SELL(또는 기존 시장가 SELL)이 409 `INSUFFICIENT_QTY`로 거부됨을 검증 — "기존 시장가 SELL도 원장을 반영" 완료조건의 핵심 증거.

## Decision Gate (spec.md 재확인, 변경 없음)

- 부분체결·슬리피지 허용 여부는 이번 범위에 포함하지 않는다(전량 목표가 체결만).
- 외부 큐 도입 여부는 착수하지 않는다 — 리스너 경계(`LimitOrderTriggerListener`)만 열어둔다.
- `AccountSummaryResponse`·`HoldingListItemResponse`에 예약분 노출은 이번 범위가 아니다.

## 알려진 한계 (spec.md와 동일, 재확인)

- 시장가 매수 경로에는 계좌 락을 추가하지 않는다 — 지정가 매수 생성과 시장가 매수 체결 간 경합은 이번 PR로 완전히 막히지 않는다. 후속 이슈로 남긴다.

---

# LMT-003 지정가 주문 취소 — 구현 계획 (이슈 #218)

## 기존 코드 현황 (구현 전 확인한 사실)

- LMT-001~002가 도입한 잠금 인프라를 그대로 재사용할 수 있다. 신규 repository 메서드는 필요 없다.
  - `OrderRepository.findByIdForUpdate(Long id)` — order 락(존재 확인 겸용).
  - `AccountService.getAccountByIdForUpdate(Long accountId)` — account 락(`LimitOrderFillService.fillIfPending`이 이미 쓰는 메서드 그대로).
  - `PortfolioSellService.getHoldingForUpdate(Account account, Instrument instrument)` — holding 락(SELL만, `LimitOrderFillService.fillSell`이 이미 쓰는 메서드 그대로). 예약된 holding이 없으면 원장 불변식 위반으로 보고 `IllegalStateException`을 던지는 기존 동작을 그대로 이용한다 — SELL 취소 대상은 생성 시 이미 `reserveQuantity`로 holding이 존재함을 보장했으므로 정상 흐름에서 없을 수 없다.
- `Order` 엔티티에 취소 상태·메서드가 없다 — `OrderStatus.CANCELLED` 추가, `Order.cancel()` 추가 필요.
- `Account`에 예약 취소용 메서드가 없다 — LMT-001 plan.md가 이미 "`releaseReservedCash`(취소용)는 이번 PR에서 쓰이지 않으므로 추가하지 않는다 — LMT-003에서 추가한다"로 예고해둔 자리다.
- `Holding.releaseReservedQuantity(BigDecimal qty)`는 이미 존재한다(`LimitOrderFillService.fillSell`이 체결 확정 시 예약 해제 용도로 이미 쓰고 있음). 취소는 이 메서드를 "확정 없이 해제만" 하는 용도로 그대로 재사용한다 — 추가 구현 불필요.
- `orders.status`는 이미 `VARCHAR(10)`이다. `"CANCELLED"`는 9자로 컬럼 변경 없이 저장 가능 — **이번 절에는 신규 Flyway 마이그레이션이 없다.**

## API 설계

### `DELETE /api/orders/{orderId}`

- 인증 필요. `Idempotency-Key` 헤더 불필요(DELETE는 멱등이며, 재호출은 이미 `CANCELLED`/`FILLED` 상태를 보고 409로 자연히 거부된다 — 기존 `DELETE /api/community/posts/{postId}` 등 이 코드베이스의 다른 DELETE 엔드포인트도 이 헤더를 쓰지 않는다).
- 성공 204, 본문 없음("확정된 설계 결정" 8번).

**오류 코드**

| HTTP | 코드 | 조건 |
|---|---|---|
| 404 | NOT_FOUND | `orderId`에 해당하는 주문 없음(기존 order 도메인 404 재사용 — 신규 코드 아님) |
| 403 | FORBIDDEN | 주문의 `user` ≠ 요청자(기존 공용 코드 재사용) |
| 409 | ORDER_ALREADY_FILLED | 주문의 `status`가 이미 `FILLED` — **신규 코드** |
| 409 | ORDER_ALREADY_CANCELLED | 주문의 `status`가 이미 `CANCELLED` — **신규 코드** |

`ErrorCode`에 `ORDER_ALREADY_FILLED(HttpStatus.CONFLICT, "이미 체결된 주문입니다.")`·`ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 주문입니다.")`를 추가한다(`IDEMPOTENCY_CONFLICT`·`UNSUPPORTED_ORDER_TYPE` 근처, order 도메인 오류들과 같은 블록에 배치). 2026-08-05 사용자 확인(spec.md "확정된 설계 결정" 9번) — 클라이언트가 두 사유를 구분해 다른 안내를 보여줄 수 있도록 단일 `ORDER_NOT_PENDING`(초안)에서 분리했다.

## 데이터 모델

마이그레이션 없음(위 "기존 코드 현황" 참고). `orders.status` 컬럼에 새 값 `"CANCELLED"`가 추가될 뿐 스키마는 불변이다.

### 엔티티 변경

**`OrderStatus`**: `CANCELLED` 추가.

```java
public enum OrderStatus {
    FILLED,
    PENDING,
    CANCELLED
}
```

**`Order`**: 취소 확정 메서드 추가(`markFilled()`와 대칭 구조).

```java
public void cancel() {
    if (this.status != OrderStatus.PENDING) {
        throw new IllegalStateException("PENDING 상태의 주문만 취소할 수 있습니다.");
    }
    this.status = OrderStatus.CANCELLED;
}
```

서비스 계층은 `markFilled()`/`reserveCash()` 호출부와 동일한 관례로 **entity 메서드 호출 전에** `order.getStatus()`를 확인해 `FILLED`면 `BusinessException(ORDER_ALREADY_FILLED)`, `CANCELLED`면 `BusinessException(ORDER_ALREADY_CANCELLED)`를 던진다(entity의 `IllegalStateException`은 방어적 이중 체크, 정상 흐름에서는 도달하지 않는다).

**`Account`**: 예약 취소 메서드 추가(`Holding.releaseReservedQuantity`와 대칭 구조).

```java
public void releaseReservedCash(long amount) {
    if (amount > this.reservedCash) {
        throw new IllegalStateException("예약된 금액보다 큰 금액을 해제할 수 없습니다.");
    }
    this.reservedCash -= amount;
}
```

`Holding`은 변경 없음 — `releaseReservedQuantity`를 그대로 재사용한다.

## 패키지·클래스 설계

| 클래스 | 패키지 | 역할 |
|---|---|---|
| `LimitOrderCancelService` | `order.service` | 취소 1건 처리(`LimitOrderFillService`와 병렬 클래스 — 같은 잠금 순서, 반대 방향 연산) |

`OrderController`(기존 파일)에 `@DeleteMapping("/{orderId}")` 메서드를 추가한다 — 새 컨트롤러를 만들지 않는다.

```java
@DeleteMapping("/{orderId}")
public ResponseEntity<Void> cancelLimitOrder(
    @AuthenticationPrincipal AuthenticatedUser principal,
    @PathVariable Long orderId) {
    limitOrderCancelService.cancelOrder(principal.userId(), orderId);
    return ResponseEntity.noContent().build();
}
```

## 취소 흐름 (LMT-003)

`LimitOrderCancelService.cancelOrder(Long userId, Long orderId)`(`@Transactional`):

1. `order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))` — **주문 락 + 존재(404) 검증을 한 번에 수행**(LMT-002 `fillIfPending`과 같은 지점, 값만 예외로 다름 — 체결 리스너는 방어적 `IllegalStateException`을 쓰지만 이쪽은 사용자 입력에서 오는 404이므로 `BusinessException`).
2. `!order.getUser().getId().equals(userId)`면 `BusinessException(ErrorCode.FORBIDDEN)` — **소유(403) 검증**. order 락은 이미 잡은 상태지만, 소유자가 아니면 이후 account/holding 락은 잡지 않고 즉시 반환한다(트랜잭션 롤백으로 order 락도 해제).
3. `order.getStatus() == OrderStatus.FILLED`면 `BusinessException(ErrorCode.ORDER_ALREADY_FILLED)`, `order.getStatus() == OrderStatus.CANCELLED`면 `BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED)` — **상태(409) 검증**(`PENDING`·`FILLED`·`CANCELLED` 3값뿐이라 이 두 분기로 배타적).
4. `account = accountService.getAccountByIdForUpdate(order.getAccount().getId())` — **계좌 락**(LMT-002와 동일한 두 번째 단계).
5. `amount = FLOOR(quantity × limitPrice)`, `fee = FLOOR(amount × CRYPTO_FEE_RATE)`(생성·체결과 동일 계산 재사용 — `LimitOrderCreationService.calculateCashRequired`·`LimitOrderFillService.fillIfPending`이 이미 각각 사설 메서드로 중복해온 것과 같은 패턴으로, 이 클래스도 사설 메서드로 한 번 더 중복한다. 011 plan.md 전례처럼 규모가 작아 공용 추출보다 복제가 낫다고 판단).
6. **BUY**: `account.releaseReservedCash(amount + fee)`.
7. **SELL**: `holding = portfolioSellService.getHoldingForUpdate(account, order.getInstrument())` — **holding 락**(세 번째 단계, SELL만) → `holding.releaseReservedQuantity(quantity)`.
8. `order.cancel()`.

**잠금 순서**(LMT-002와 동일, "확정된 설계 결정" 7·8번 재확인): `order → account → (SELL만 holding)`.

## 동시성 테스트 시나리오 추가 (`LimitOrderConcurrencyIntegrationTest`, 기존 `runConcurrently` 헬퍼 재사용)

기존 파일의 `runConcurrently`(ready/start `CountDownLatch` 2단계, `ExecutorService` 2스레드, `Future.get`으로 예외 전파)를 그대로 재사용해 새 테스트 메서드를 추가한다 — 새 유틸리티를 만들지 않는다.

1. **취소-대-체결 경합(시나리오 12)**: `PENDING` 지정가 매수 주문 1건을 만든 뒤, 스레드 A는 `limitOrderCancelService.cancelOrder(userId, orderId)`, 스레드 B는 `limitOrderFillService.fillIfPending(orderId)`를 동시에 호출한다. 두 결과 조합 중 정확히 하나만 성립함을 검증한다:
   - **체결이 이겼다면**: `orderRepository.findById(orderId)`가 `FILLED`, `cancelOrder` 호출은 `BusinessException(ORDER_ALREADY_FILLED)`를 던짐, `Trade` 1건 생성, `reservedCash`는 0(체결로 소비).
   - **취소가 이겼다면**: `orderRepository.findById(orderId)`가 `CANCELLED`, `fillIfPending` 호출은 예외 없이 조용히 반환(no-op), `Trade` 0건, `reservedCash`는 0(취소로 반환).
   - 두 경우 모두 `cashBalance`는 시작값(체결이 이긴 경우만 실제 지출로 감소, 취소가 이긴 경우는 불변)과 정확히 일치해 예약 이중 반환·이중 소비가 없음을 확인한다.
2. 매도 지정가에 대해서도 같은 패턴으로 1개 더 추가한다(`holding.reservedQuantity`/`quantity` 버전) — BUY 시나리오와 대칭이므로 assert 대상만 계좌 대신 holding으로 바꾼다.
3. **정상 취소 단위 테스트**(`LimitOrderCancelServiceTest`, `@ExtendWith(MockitoExtension.class)` 단위): BUY 취소 시 `releaseReservedCash` 호출값 검증, SELL 취소 시 `releaseReservedQuantity` 호출값 검증, 존재하지 않는 주문 404, 타인 소유 403, 이미 `FILLED`/이미 `CANCELLED` 409(두 상태 모두 개별 케이스), 검증 순서(존재→소유→상태) 순서 준수 여부(모킹으로 호출 순서 확인 또는 각 실패 케이스가 이후 단계 호출을 하지 않음을 검증).

---

# 시장가 매수 경로 락 보강 — 구현 계획 (이슈 #224)

## 관련 문서

- Spec: `./spec.md` "시장가 매수 경로 락 보강 (이슈 #224)" 절, "확정된 설계 결정" 10번.
- 이 절이 조정하는 두 클래스는 위 LMT-001~002 계획이 이미 도입한 잠금 인프라(§ "Repository 추가 메서드", "잠금 순서 요약")를 그대로 재사용한다 — 신규 repository·서비스 메서드가 필요 없다.

## 기존 코드 확인 결과 (착수 전 직접 확인한 사실)

- **`OrderExecutionService.createBuyOrder`**(현재 76행)는 `Account account = getAccountFor(userId, request.market());`로 **락 없이** 계좌를 조회한다. 같은 클라이언트 안에 이미 `getAccountForUpdateFor(userId, market)`(200~204행, `accountService.getAccountForUpdate` 위임)라는 **락 버전 private 메서드가 존재**하고 `createSellOrder`가 이미 이 메서드를 쓰고 있다 — BUY 경로도 이 메서드 하나로 교체하면 끝난다. 새 `AccountService` 메서드는 필요 없다.
- **`PortfolioBuyService.applyBuyTrade`**(23~40행)는 `holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())`로 **락 없이** holding을 조회한 뒤 `orElseGet(() -> Holding.create(...))`으로 신규 생성 분기를 탄다. `HoldingRepository`에는 이미 SELL 경로(LMT-001/002)가 쓰는 `findByAccountIdAndInstrumentIdForUpdate(accountId, instrumentId)`(`@Lock(PESSIMISTIC_WRITE)`, 반환 타입 `Optional<Holding>` 동일)가 존재한다 — **메서드 이름만 바꾸면 나머지 `orElseGet` 로직은 그대로 컴파일된다.** 신규 repository 메서드도 필요 없다.
- **`applyBuyTrade` 호출부는 정확히 2곳**: (1) `OrderExecutionService.createBuyOrder` 108행(시장가 매수, HTTP 스레드), (2) `LimitOrderFillService.fillBuy` 81행(지정가 매수 체결, 가격 피드 스레드). (2)는 이미 `fillIfPending`에서 `accountService.getAccountByIdForUpdate(...)`로 account 락을 잡은 뒤 `fillBuy`에 진입하므로, `applyBuyTrade` 한 곳만 고치면 (1)·(2) 양쪽 호출부가 동시에 holdings 락으로 보호된다. (1)은 이번 변경으로 account 락을 추가하면 마찬가지로 "account 먼저 → applyBuyTrade(holding 락)" 순서가 자동으로 성립한다.
- 결론: **신규 repository/서비스 메서드가 전혀 필요 없다.** 기존 메서드 참조를 두 줄 교체하는 것만으로 완료조건을 충족한다. 이슈 원문이 예시로 든 "`getOrPrepareHoldingForUpdate` 같은 감싸는 신규 메서드"는 필요하지 않다고 판단한다 — `PortfolioBuyService`가 이미 같은 portfolio 도메인의 `HoldingRepository`를 직접 주입해 두고 있어(ADR-0002 위반 아님), 래퍼 없이 호출 메서드만 바꾸는 편이 더 적은 변경이다.

## 변경 지점

### 1. `OrderExecutionService.createBuyOrder`

```java
// 변경 전 (75~76행)
// 매수 경로는 이번에 변경하지 않는다(spec.md "알려진 한계") — 계좌 락 없이 그대로 조회한다.
Account account = getAccountFor(userId, request.market());

// 변경 후
// 매수도 매도와 동일하게 계좌를 먼저 잠근다(spec.md "시장가 매수 경로 락 보강", 이슈 #224).
Account account = getAccountForUpdateFor(userId, request.market());
```

- `getAccountFor(Long, Market)` private 메서드는 이제 어떤 호출부도 쓰지 않게 된다(SELL은 이미 `getAccountForUpdateFor`, BUY도 이번에 전환) — 구현 시 실제로 다른 호출부가 없는지 확인 후 제거하거나, 향후 락 없는 조회가 필요해질 가능성을 남겨 유지할지는 구현자가 grep으로 확인해 판단한다(둘 다 허용, 컨벤션 위반 아님 — 죽은 코드면 제거가 원칙).
- `getAccountForUpdateFor` 메서드 위 주석("시장가 매도만 계좌를 잠가 조회한다")을 "시장가 매수·매도 모두 계좌를 잠가 조회한다"로 갱신한다.
- `execute()`의 계좌 선조회 제거 주석(66행)은 이미 "매수·매도가 서로 다른 락 전략을 쓰므로"라고 적어뒀는데, 이번 변경 후에는 두 전략이 "둘 다 계좌를 잠근다"는 점에서 동일해진다 — 주석을 "매수·매도 모두 각자 계좌를 잠가 조회한다(호출 시점·인자만 다름)"로 정정한다.

### 2. `PortfolioBuyService.applyBuyTrade`

```java
// 변경 전 (31~33행)
Holding holding = holdingRepository
    .findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
    .orElseGet(() -> Holding.create(account, instrument, now));

// 변경 후
Holding holding = holdingRepository
    .findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
    .orElseGet(() -> Holding.create(account, instrument, now));
```

- 클래스 상단 주석("매수 체결 결과를 보유(holding)·매수 lot에 반영하는 서비스")과 메서드 위에, 이 조회가 이제 비관적 락임을 명시하는 한 줄 추가: "holdings row를 잠근 뒤 갱신한다(시장가·지정가 매수 체결 공통 호출부, 이슈 #224) — 신규 종목 첫 매수(row 없음)는 호출부가 이미 잡은 account 락만으로 동시 생성 경합을 막는다(spec.md 확정된 설계 결정 10번, 방어적 유니크 제약 catch 없음)."
- `findByAccountIdAndInstrumentId`(락 없는 원본 메서드)의 다른 호출부가 남아있는지 구현 시 grep으로 확인한다(있으면 유지, 없으면 죽은 메서드로 제거 검토 — 이 plan 작성 시점 확인으로는 `PortfolioBuyService`가 유일한 호출부였다).

## 잠금 순서 요약 (갱신)

| 흐름 | 순서 |
|---|---|
| LMT-001 BUY 생성 | account |
| LMT-001 SELL 생성 | holding |
| LMT-002 BUY 체결 | order → account → holding(있으면) |
| LMT-002 SELL 체결 | order → account → holding |
| 시장가 SELL 체결(LMT 조정) | account → holding |
| **시장가 BUY 체결(이슈 #224 조정)** | **account → holding(있으면)** |

모든 흐름이 "account가 holding보다 항상 먼저"를 지키므로 ABBA 사이클이 생기지 않는다. LMT-002 BUY 체결의 "holding(있으면)"은 이번 변경 후 `findByAccountIdAndInstrumentIdForUpdate`가 항상 실행되지만, 매칭되는 row가 없으면 잠글 대상 자체가 없다는 뜻이다(신규 종목 첫 매수는 account 락만으로 방지, 확정된 설계 결정 10번).

## 동시성 테스트 시나리오 (`LimitOrderConcurrencyIntegrationTest`에 추가, 기존 `runConcurrently` 헬퍼 재사용)

spec.md 시나리오 13·14·15에 대응한다.

1. **계좌 경합(시나리오 13)**: 계좌에 초기 현금을 준비한다. 스레드 A는 `orderExecutionService.execute(...)`(시장가 매수, 현금 대부분 소비), 스레드 B는 `limitOrderService.createLimitOrder(...)`(지정가 매수 생성, 남은 현금 대부분 예약)를 동시에 호출한다 — 두 요청을 합친 소비액이 원래 잔액을 초과하도록 금액을 설계한다. 검증: 정확히 한쪽만 성공하고 다른 한쪽은 409 `INSUFFICIENT_CASH`로 거부되거나, 직렬화된 순서에 따라 뒤에 처리된 쪽이 앞선 갱신을 반영한 `availableCash`로 정확히 검증됨(무엇이 이기든 `cashBalance - reservedCash`가 음수가 되지 않음을 최종 상태로 확인). 데드락·타임아웃 없이 완료돼야 한다.
2. **holdings lost-update 경합(시나리오 14)**: 이미 수량 Q0을 보유한 holding을 준비한다. 스레드 A는 `orderExecutionService.execute(...)`(시장가 매수, 수량 qA), 스레드 B는 이미 `PENDING`인 지정가 매수 주문에 대해 `limitOrderFillService.fillIfPending(orderId)`(수량 qB)를 동시에 호출한다. 검증: 최종 `holding.quantity == Q0 + qA + qB`(둘 다 반영, 하나가 유실되지 않음), `HoldingLot`이 2건 모두 생성됨, 평균단가가 두 매수 가격을 모두 반영해 계산됨.
3. **ABBA 데드락 회귀(시나리오 15)**: 스레드 A는 조정된 시장가 매수(`OrderExecutionService.createBuyOrder`, account→holding), 스레드 B는 기존 시장가 매도 또는 지정가 SELL 체결(이미 account→holding)을 서로 다른 주문으로 같은 계좌·holding에 대해 동시에 실행한다. 둘 다 타임아웃 없이 완료됨을 검증한다(반대 순서로 잠그는 흐름이 없으므로 데드락 예외가 나면 회귀).

## Decision Gate (spec.md 확정된 설계 결정 10번 재확인, 변경 없음)

- 신규 종목 첫 매수 동시 생성 경합은 account 락만으로 방지한다 — `holdings.uk_holdings_account_instrument` 유니크 제약 위반에 대한 방어적 catch·재조회 로직은 추가하지 않는다(일어날 수 없는 시나리오에 방어 코드를 넣지 않는다는 컨벤션과 일치).
- 마이그레이션·API 계약 변경 없음 — 락 순서 조정은 서비스 레이어 내부 구현이라 `docs/api-routes.md`·`docs/api-contracts.md`·`docs/prd.md` §3 갱신 대상이 아니다(CLAUDE.md 규칙7은 controller 변경 시에만 적용, 규칙10은 기능 제공 범위가 그대로인 변경이라 갱신 비대상).

---

# LMT-004 미체결 주문 목록 조회 · 계좌·보유 조회 계약 영향 해소 — 구현 계획 (이슈 #235)

## 관련 문서

- Spec: `./spec.md` "LMT-004 미체결 주문 목록 조회" 절, "계좌·보유 조회 계약 영향 해소 (Decision Gate)" 절, "확정된 설계 결정" 11번.
- 형제 API(패턴 원본): `GET /api/orders?market=&cursor=&limit=`(PORT-003, `docs/specs/018-order-list-pagination/plan.md`) — `OrderCursor`·`OrderListItemResponse`·`OrderListResponse`·`OrderRepositoryCustom`/`OrderRepositoryImpl`(QueryDSL) 커서 페이지네이션 인프라를 그대로 재사용한다. 상태 필터만 추가되는 변형이라 새 클래스를 최소화한다.
- PRD 근거: `docs/prd.md` 626~634행(LMT-004 + "계좌·보유 조회 계약 영향(Decision Gate)"), §3 구현 현황 213행.
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service만 — `HoldingListItemResponse`가 `Holding` 엔티티를 그대로 받는 기존 패턴을 유지), [ADR-0003](../../adr/0003-testing-strategy.md)(테스트 전략), [ADR-0004](../../adr/0004-flyway-migrations.md)(이번 작업은 스키마 변경 없음 — `accounts.reserved_cash`·`holdings.reserved_quantity`는 V22로 이미 존재).

## 기존 코드 현황 (구현 전 확인한 사실)

- `GET /api/orders?market=&cursor=&limit=`(018-order-list-pagination, PORT-003)가 이미 이번에 필요한 커서 페이지네이션 인프라 전체를 구현해뒀다:
  - `OrderCursor`(`src/main/java/com/finplay/api/order/service/OrderCursor.java`) — `{ISO_LOCAL_DATE_TIME}_{id}` 포맷, `parse(String)`(손상 시 `BusinessException(VALIDATION_ERROR)`)·`encode(Order)` 정적 메서드.
  - `OrderRepositoryCustom.findByAccountIdWithCursor(accountId, cursorRequestedAt, cursorId, fetchSize)` / `OrderRepositoryImpl`(QueryDSL, `QOrder`, `account.id.eq` + 커서 이전 조건 + `requestedAt desc, id desc` + `instrument` fetchJoin).
  - `OrderListItemResponse.from(Order)`(orderId·market·instrumentId·side·orderType·status·quantity·requestedAt), `OrderListResponse(content, nextCursor, hasNext)`.
  - `OrderController`의 `DEFAULT_LIMIT`(20)·`MIN_LIMIT`(1)·`MAX_LIMIT`(100) 상수와 `validateLimit(int)`(범위 밖이면 `BusinessException(VALIDATION_ERROR)`, 클램핑 없음).
  - `OrderService.getMyOrders(userId, market, cursor, limit)`가 `accountService.getAccountFor(userId, market)`로 소유권+시장 스코프를 먼저 검증한 뒤 `limit+1` fetch로 `hasNext`를 판정하는 패턴.
  - 이번 작업은 이 인프라 전체를 재사용하고 "상태가 `PENDING`인 것만" 조건 하나만 얹는다 — 신규 커서 포맷·신규 응답 DTO를 만들지 않는다.
- `OrderRepository`에는 이미 `findByIdForUpdate`(단건 락)·`findPendingLimitOrdersToFill`(단건 조건 조회, 커서 없음)이 있지만 둘 다 이번 목록 조회 용도가 아니다 — `OrderRepositoryCustom`에 상태 필터가 있는 커서 조회 메서드가 새로 필요하다.
- `Account.reservedCash`(`long`, `@Getter`로 `getReservedCash()` 이미 공개)·`Holding.reservedQuantity`(`BigDecimal`, `@Getter`로 `getReservedQuantity()` 이미 공개)는 015-limit-order LMT-001(V22)이 이미 도입했다 — 엔티티·마이그레이션 변경이 전혀 필요 없다.
- `AccountService.getAccountSummary`(`src/main/java/com/finplay/api/account/service/AccountService.java` 91~119행)는 `AccountSummaryResponse.of(cashBalance, holdingsValue, totalValue, realizedPnl, unrealizedPnl, returnRate)`를 호출한다 — `account.getReservedCash()`를 인자 하나 추가하면 된다. `totalValue`(=`cashBalance + holdingsValue`) 등 기존 계산식은 전혀 바꾸지 않는다.
- `HoldingService.getHoldings`(`src/main/java/com/finplay/api/portfolio/service/HoldingService.java` 24~33행)는 `HoldingListItemResponse.of(holdings.get(i), valuations.get(i))`를 호출한다 — 이미 `Holding` 엔티티를 통째로 넘기므로, `HoldingListItemResponse.of` 내부에서 `holding.getReservedQuantity()`를 추가로 읽기만 하면 되고 `HoldingService`·`HoldingValuationService`·`HoldingValuationDto`는 손댈 필요가 없다.

## API 설계

### `GET /api/orders/pending?market=&cursor=&limit=`

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/orders/pending?market=&cursor=&limit=` | `market`(필수), `cursor`(선택), `limit`(선택, 기본 20) | `OrderListResponse`(기존 타입 재사용, 신규 DTO 아님) | 인증 사용자 본인의 `market` 계좌가 보유한 `PENDING` 상태 지정가 주문을 최신순(동시각 `id` 내림차순) 커서 페이지네이션으로 조회 |

- 인증·계좌 소유권 검증(`accountService.getAccountFor(userId, market)`)·오류 원칙은 `GET /api/orders`(PORT-003)와 동일하다.
- 이 코드베이스에서 `status = PENDING`은 현재 지정가(`orderType = LIMIT`) 주문만 가질 수 있다(시장가는 생성 즉시 `FILLED`) — 그래서 쿼리에 `orderType` 조건을 별도로 추가하지 않아도 결과는 자동으로 지정가 주문만 포함한다(일어날 수 없는 조합에 방어 조건을 추가하지 않는다는 관례와 일치, `docs/conventions.md`).
- `market=STOCK`으로 요청해도 400으로 거부하지 않는다 — `GET /api/orders`(PORT-003)와 동일하게 `STOCK`\|`CRYPTO` 모두 유효한 조회 대상이다. 현재 주식 지정가가 없으므로 결과는 자연히 빈 배열이 된다. LMT-001 생성 API의 "market≠CRYPTO면 400"(이건 주문 생성 제약)과 이 조회 API의 "market은 조회 대상 시장을 고르는 필수 파라미터"(PORT-002 규칙)는 서로 다른 성격의 검증이라 혼동하지 않는다.

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `market` | Y | `com.finplay.api.account.domain.Market`(enum: `STOCK`\|`CRYPTO`) — **`market.domain.Market`이 아니다**(기존 006/018 plan.md의 "Market 타입 주의" 절과 동일 이유, `accountService.getAccountFor(userId, market)` 호출부와 타입을 맞춰야 컴파일된다). 누락·미지원 리터럴은 기존 `GlobalExceptionHandler`가 이미 400 `VALIDATION_ERROR`로 매핑(컨트롤러에 별도 코드 불필요) |
| `cursor` | N | `{ISO_LOCAL_DATE_TIME}_{id}` 형식 문자열. 생략 시 첫 페이지. 파싱 실패 400 `VALIDATION_ERROR`(`OrderCursor.parse` 재사용) |
| `limit` | N | 기본 20, 정수. 1~100 범위 밖 400 `VALIDATION_ERROR`(`OrderController.validateLimit` 재사용, 클램핑 없음) |

계좌 소유권: `market`으로 조회한 계좌가 존재하지 않거나 타인 소유면 `accountService.getAccountFor`가 `BusinessException(ErrorCode.NOT_FOUND)`를 던진다(PORT-003과 동일 근거 — 요청에 타인 식별자를 받지 않는 구조라 403 분기가 없다).

## 데이터 모델

신규 테이블·컬럼·마이그레이션 없음(ADR-0004). `orders` 테이블 기존 컬럼(`account_id`·`status`·`requested_at`·`id`)으로 조회한다. 018 plan.md가 `(account_id, requested_at, id)` 인덱스를 필수 항목으로 두지 않은 것과 동일하게, `status` 조건이 추가된다고 해서 신규 인덱스를 이번 범위에 필수로 포함하지 않는다(코인 전용이라 `PENDING` 행 자체가 소규모, 성능 이슈로 재판단 가능).

## Repository 설계

`OrderRepositoryCustom`에 상태 필터가 있는 메서드를 추가한다(기존 `findByAccountIdWithCursor`는 그대로 두어 PORT-003 회귀를 만들지 않는다 — 병렬 메서드로 추가).

```java
// OrderRepositoryCustom
List<Order> findByAccountIdAndStatusWithCursor(
    Long accountId, OrderStatus status, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize);
```

```java
// OrderRepositoryImpl (신규 메서드, 기존 findByAccountIdWithCursor와 병렬 — 구조 동일, status 조건만 추가)
@Override
public List<Order> findByAccountIdAndStatusWithCursor(
    Long accountId, OrderStatus status, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize) {
    QOrder order = QOrder.order;

    BooleanBuilder condition = new BooleanBuilder(order.account.id.eq(accountId))
        .and(order.status.eq(status));
    if (cursorRequestedAt != null && cursorId != null) {
        condition.and(
            order.requestedAt.lt(cursorRequestedAt)
                .or(order.requestedAt.eq(cursorRequestedAt).and(order.id.lt(cursorId))));
    }

    return queryFactory
        .selectFrom(order)
        .join(order.instrument).fetchJoin()
        .where(condition)
        .orderBy(order.requestedAt.desc(), order.id.desc())
        .limit(fetchSize)
        .fetch();
}
```

## Service 설계

`OrderService`에 `getMyOrders`와 병렬인 메서드를 추가한다(상태 필터만 다름, 나머지 흐름 동일):

```java
@Transactional(readOnly = true)
public OrderListResponse getMyPendingOrders(Long userId, Market market, String cursor, int limit) {
    Account account = accountService.getAccountFor(userId, market);
    OrderCursor parsedCursor = OrderCursor.parse(cursor);

    List<Order> fetched = orderRepository.findByAccountIdAndStatusWithCursor(
        account.getId(),
        OrderStatus.PENDING,
        parsedCursor == null ? null : parsedCursor.requestedAt(),
        parsedCursor == null ? null : parsedCursor.id(),
        limit + 1);

    boolean hasNext = fetched.size() > limit;
    List<Order> page = hasNext ? fetched.subList(0, limit) : fetched;
    String nextCursor = hasNext ? OrderCursor.encode(page.get(page.size() - 1)) : null;

    List<OrderListItemResponse> content = page.stream().map(OrderListItemResponse::from).toList();
    return OrderListResponse.of(content, nextCursor, hasNext);
}
```

- `OrderListItemResponse`·`OrderListResponse`·`OrderCursor` 모두 신규 DTO 없이 재사용한다 — 이슈 #235 본문이 별도 응답 필드를 요구하지 않으므로 `GET /api/orders`와 동일한 8개 필드(orderId·market·instrumentId·side·orderType·status·quantity·requestedAt)면 충분하다. 이 목록에서는 `status`가 항상 `"PENDING"`이지만, 신규 DTO를 만드는 유지비용이 필드 1개 고정값의 이점보다 크므로 재사용을 택한다.

## Controller 설계

```java
// OrderController(기존 파일)에 추가 — DEFAULT_LIMIT/MIN_LIMIT/MAX_LIMIT·validateLimit 재사용
@GetMapping("/pending")
public ResponseEntity<OrderListResponse> getMyPendingOrders(
    @AuthenticationPrincipal AuthenticatedUser principal,
    @RequestParam Market market,
    @RequestParam(required = false) String cursor,
    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
    validateLimit(limit);
    return ResponseEntity.ok(orderService.getMyPendingOrders(principal.userId(), market, cursor, limit));
}
```

- 기존 `OrderController`(`POST`·`POST /limit`·`DELETE /{orderId}`·`GET`이 이미 있는 클래스)에 메서드만 추가한다. 새 컨트롤러 클래스를 만들지 않는다.
- 경로 충돌 없음: `@GetMapping("/pending")`과 기존 `@DeleteMapping("/{orderId}")`는 HTTP 메서드가 달라 매핑이 겹치지 않는다. 다만 `@GetMapping`끼리는(`GET /api/orders`와 `GET /api/orders/pending`) 고정 경로(`/pending`)가 변수 경로(`GET /{orderId}` 같은 것)보다 우선 매치되는 Spring MVC 규칙을 따르므로, 이 컨트롤러에 향후 `GET /{orderId}`(단건 조회)가 추가되면 `/pending` 매핑이 그보다 먼저 선언돼 있어야 충돌하지 않는다는 점만 기록해둔다(현재는 그런 메서드가 없어 영향 없음).

## 계좌·보유 조회 계약 영향 해소 (Decision Gate, PRD 634행)

### `AccountSummaryResponse.reservedCash`

```java
// AccountSummaryResponse (기존 record, 필드·of(...) 인자 추가)
public record AccountSummaryResponse(
    long cashBalance,
    long reservedCash,   // 신규
    long holdingsValue,
    long totalValue,
    long realizedPnl,
    long unrealizedPnl,
    BigDecimal returnRate) {

    public static AccountSummaryResponse of(
        long cashBalance,
        long reservedCash,   // 신규
        long holdingsValue,
        long totalValue,
        long realizedPnl,
        long unrealizedPnl,
        BigDecimal returnRate) {
        return new AccountSummaryResponse(
            cashBalance, reservedCash, holdingsValue, totalValue, realizedPnl, unrealizedPnl, returnRate);
    }
}
```

- 필드 위치는 `cashBalance` 바로 다음(예약분이 현금 계열 값이라 의미상 인접 배치, 다른 필드 순서는 바꾸지 않는다).
- `AccountService.getAccountSummary`의 `AccountSummaryResponse.of(...)` 호출(118행 부근)에 `account.getReservedCash()` 인자를 추가한다. `totalValue`(=`cashBalance + holdingsValue`) 계산식은 변경하지 않는다 — `reservedCash`는 원장 값을 그대로 보여주는 추가 정보일 뿐, 기존 필드가 예약분을 반영하도록 재계산하지 않는다(spec.md "계좌·보유 조회 계약 영향 해소" 절 근거).
- "주문 가능 금액"을 뜻하는 `availableCash` 같은 파생 필드는 추가하지 않는다(spec.md "확정된 설계 결정" 11번) — 클라이언트가 `cashBalance - reservedCash`로 직접 계산할 수 있다.

### `HoldingListItemResponse.reservedQuantity`

```java
// HoldingListItemResponse (기존 record, 필드 추가 — of(Holding, HoldingValuationDto) 시그니처는 그대로)
public record HoldingListItemResponse(
    Long instrumentId,
    String symbol,
    String name,
    BigDecimal quantity,
    BigDecimal reservedQuantity,   // 신규
    BigDecimal averagePrice,
    BigDecimal currentPrice,
    Long evaluationAmount,
    Long unrealizedPnl,
    BigDecimal returnRate,
    String priceStatus) {

    public static HoldingListItemResponse of(Holding holding, HoldingValuationDto valuation) {
        return new HoldingListItemResponse(
            holding.getInstrument().getId(),
            holding.getInstrument().getSymbol(),
            holding.getInstrument().getName(),
            valuation.quantity(),
            holding.getReservedQuantity(),   // 신규 — valuation이 아니라 holding에서 직접 읽는다
            valuation.averagePrice(),
            valuation.currentPrice(),
            valuation.evaluationAmount(),
            valuation.unrealizedPnl(),
            valuation.returnRate(),
            valuation.priceStatus().name());
    }
}
```

- 필드 위치는 `quantity` 바로 다음(총 보유수량과 예약수량을 나란히 비교하기 쉽게 배치).
- `reservedQuantity`는 `HoldingValuationDto`가 아니라 `holding` 엔티티에서 직접 읽는다 — `HoldingValuationService.evaluateHolding`은 시세·손익 계산만 책임지고 예약 원장과는 무관하므로(관심사 분리), `HoldingValuationDto`에 필드를 추가하지 않는다. 006 plan.md가 이미 "계산 재사용과 표현 정책은 별개 결정"이라고 확립한 전례와 같은 판단이다 — 계산 로직·반올림 규칙은 전혀 바꾸지 않는다.
- `HoldingService.getHoldings` 호출부는 변경 없음 — 이미 `holding`을 통째로 `HoldingListItemResponse.of(...)`에 넘기므로, DTO 내부에서 `holding.getReservedQuantity()`를 추가로 읽기만 하면 된다.
- "주문 가능 수량"을 뜻하는 `availableQuantity` 같은 파생 필드는 추가하지 않는다(spec.md "확정된 설계 결정" 11번) — 클라이언트가 `quantity - reservedQuantity`로 직접 계산할 수 있다.

### 영향받는 기존 테스트 (컴파일 수정 필요, 006 plan.md 454행이 예고한 것과 동일 성격의 회귀)

- `AccountServiceTest` — `AccountSummaryResponse.of(...)`·`new HoldingValuationDto(...)` 관련 호출부에 인자 추가. `getAccountSummary`가 `reservedCash`를 실제 값으로 정확히 반환하는지 검증하는 케이스를 최소 1개 추가한다(기존 시세 유효/무효 혼합 케이스와는 별개 관심사).
- `AccountControllerTest`(`@WebMvcTest`) — `jsonPath`로 `reservedCash` 필드 계약 검증 추가(기존 6개 필드 회귀 확인과 함께).
- `HoldingServiceTest` — `HoldingListItemResponse.of(...)` 검증에 `reservedQuantity` 실제값 확인 추가.
- `HoldingControllerTest`(`@WebMvcTest`) — `jsonPath`로 `reservedQuantity` 필드 계약 검증 추가.

## 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7 + 규칙 10):

- `docs/api-routes.md`: 라우트 표에 `GET | /api/orders/pending?market=&cursor=&limit= | order | ... | 015 LMT-004, Issue #235` 행 추가(기존 `GET /api/orders`·`POST /api/orders/limit`·`DELETE /api/orders/{orderId}` 행 근처).
- `docs/api-contracts.md`: `## order` 절에 "미체결 주문 목록 조회" 표 추가(요청 `market`/`cursor`/`limit`, 응답 `OrderListResponse` 예시, 오류 400/401/404). `## account` 절의 `AccountSummaryResponse` 예시에 `reservedCash` 필드를 반영. `## portfolio` 절의 `HoldingListItemResponse` 예시에 `reservedQuantity` 필드를 반영.
- `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "완료"로 갱신한다 — LMT-001~004 전부 완료됨을 명시. "계좌·보유 조회 계약 영향(Decision Gate)" 절 본문도 "착수 시 확정한다"는 미정 문구를 실제 필드명(`reservedCash`/`reservedQuantity`)이 확정됐다는 문구로 교체한다.

## 테스트 계획 (ADR-0003 기준)

- **단위(`OrderServiceTest`, 기존 파일)**: `getMyPendingOrders`가 `accountService.getAccountFor`로 계좌를 선조회하는지, `OrderRepository.findByAccountIdAndStatusWithCursor`를 `OrderStatus.PENDING` 인자로 호출하는지(Mockito `verify`), 커서 파싱·`limit+1` fetch로 `hasNext` 판정, 마지막 페이지에서 `nextCursor=null` — `getMyOrders` 테스트 패턴 그대로.
- **단위(`AccountServiceTest`, 기존 파일)**: `getAccountSummary`가 `reservedCash`를 응답에 정확히 포함하는지 실측 검증.
- **단위(`HoldingServiceTest`, 기존 파일)**: `getHoldings`가 `reservedQuantity`를 응답에 정확히 포함하는지 실측 검증.
- **슬라이스(`OrderControllerTest`, 기존 파일)**: `GET /api/orders/pending` — `market` 생략/미지원 400, `limit` 범위 밖 400, `cursor` 손상 400, 정상 요청 200과 `content`의 모든 항목이 `status="PENDING"`인지, 인증 실패 401 — 기존 `GET /api/orders` 테스트 대응 패턴.
- **슬라이스(`AccountControllerTest`·`HoldingControllerTest`, 기존 파일)**: `jsonPath`로 `reservedCash`·`reservedQuantity` 필드값 검증(0인 경우·양수인 경우 각각).
- **슬라이스(`@DataJpaTest`, 신규 또는 기존 `OrderRepositoryTest` 근처)**: `findByAccountIdAndStatusWithCursor`가 `status` 필터·`account_id` 필터·`requestedAt desc, id desc` 정렬·커서 이전 조건을 만족하는지 — 기존 `findByAccountIdWithCursor` 테스트 대응 패턴에 상태 필터 케이스 추가.
- **통합(신규 또는 기존 `LimitOrderConcurrencyIntegrationTest`/`OrderListIntegrationTest` 인접)**: 지정가 매수·매도 주문을 여러 건(`PENDING`·`FILLED`·`CANCELLED` 혼합) 생성한 뒤 `GET /api/orders/pending?market=CRYPTO` 호출 → `PENDING`만 반환되고 최신순 커서 페이지네이션이 정확한지(첫 페이지 → `nextCursor`로 다음 페이지, 중복·누락 없음), 타 사용자 주문 미노출 확인. 같은 시나리오에서 `GET /api/accounts/summary?market=CRYPTO`·`GET /api/holdings?market=CRYPTO`를 호출해 `reservedCash`·`reservedQuantity`가 실제 예약값과 정확히 일치하는지, 체결·취소 후에는 각각 0(또는 감소한 값)으로 돌아오는지 확인.

## Decision Gate (spec.md 확정된 설계 결정 11번 재확인, 변경 없음)

- `AccountSummaryResponse.reservedCash`·`HoldingListItemResponse.reservedQuantity`로 필드명을 확정했다 — `availableCash`/`availableQuantity` 같은 파생값 필드는 추가하지 않는다.
- 신규 마이그레이션 없음 — `accounts.reserved_cash`·`holdings.reserved_quantity`는 V22(015-limit-order LMT-001)로 이미 존재한다.
- ~~주문 수정(가격·수량 변경) API는 이번 범위가 아니다~~ → 이슈 #239(LMT-005)로 착수됐다. 아래 "LMT-005 지정가 주문 수정 — 구현 계획" 절 참고.

---

# LMT-005 지정가 주문 수정 — 구현 계획 (이슈 #239)

## 관련 문서

- Spec: `./spec.md` "LMT-005 지정가 주문 수정 (코인 전용, 이슈 #239)" 절, "LMT-005 완료 조건", "확정된 설계 결정" 12번(수정 이력 미보관 재확정).
- PRD 근거: `docs/prd.md` 632~649행(LMT-005 정책, PR #238로 확정).
- 이 절이 재사용하는 잠금·예약 인프라는 위 LMT-002·LMT-003 계획이 이미 도입했다 — 신규 repository 메서드는 필요 없다.

## 기존 코드 현황 (구현 전 확인한 사실)

- `LimitOrderCancelService.cancelOrder`(`src/main/java/com/finplay/api/order/service/LimitOrderCancelService.java`)가 이번 수정 흐름과 거의 동일한 골격이다: `orderRepository.findByIdForUpdate`(락+존재 404) → 소유(403) → 상태(409, `FILLED`/`CANCELLED` 개별 분기) → `accountService.getAccountByIdForUpdate`(계좌 락) → BUY는 `account.releaseReservedCash`, SELL은 `portfolioSellService.getHoldingForUpdate`(holding 락) + `holding.releaseReservedQuantity`. 수정 서비스는 이 골격에 "해제 후 재예약"을 한 번 더 얹는 형태다.
- `Order` 엔티티(`src/main/java/com/finplay/api/order/domain/Order.java`)는 현재 `limitPrice`·`quantity`를 바꾸는 메서드가 없다 — `cancel()`(153~158행)·`markFilled()`(146~151행)와 동일한 방어적 패턴(`PENDING`이 아니면 `IllegalStateException`)으로 `modify(BigDecimal quantity, BigDecimal limitPrice)`를 신설해야 한다.
- `Account`(`reserveCash`·`releaseReservedCash`·`getAvailableCash`)·`Holding`(`reserveQuantity`·`releaseReservedQuantity`·`getAvailableQuantity`)은 이미 LMT-001·LMT-003이 대칭으로 갖춰뒀다 — 엔티티 변경이 필요 없다.
- **수수료 계산 중복(이슈 #239 본문 지적)**: `CRYPTO_FEE_RATE = new BigDecimal("0.0005")`와 `FLOOR(수량×지정가)` + `FLOOR(FLOOR(수량×지정가)×0.05%)` 계산이 `LimitOrderCreationService`(33·106~111행, `calculateCashRequired`)·`LimitOrderCancelService`(25·51~56행, 인라인)·`LimitOrderFillService`(32·54~59행, 인라인) 세 곳에 완전히 동일한 형태로 중복돼 있다. 수정 서비스가 이 계산을 그대로 네 번째로 복제할지, 공통화할지 판단이 필요하다 — 아래 "수수료 계산 공통화 결정" 참고.
- `OrderController`(기존 파일)에 `POST /limit`·`DELETE /{orderId}`·`GET`·`GET /pending`이 이미 있다 — `PATCH /{orderId}` 추가만 필요.

## 수수료 계산 공통화 결정

**공통화한다.** 이유:

- 지금 3곳(생성·취소·체결)에 동일한 수식이 중복돼 있는데, 수정 서비스가 그대로 복사하면 **4곳**이 된다. 011/015 plan.md가 이전에 "규모가 작아 추상화보다 복제가 낫다"고 판단해온 전례(예: `validateMinOrderAmount` 중복)와 다른 점은, 이건 **돈 계산**이라는 것이다 — 수수료율이 바뀌거나 반올림 규칙이 바뀌면 4곳을 전부 찾아 고쳐야 하고, 하나라도 놓치면 "생성 시 예약한 금액과 체결 시 확정되는 금액이 항상 정확히 일치한다"(spec.md 비즈니스 규칙)는 이 기능 전체의 불변식이 조용히 깨진다. 검증 로직(형식 체크 등)의 복제와 달리 금액 계산의 복제는 실제 금전 버그로 이어질 위험이 있다.
- 4곳 모두 계산 자체는 완전히 동일하다(같은 인자 `quantity`·`limitPrice`, 같은 반올림 규칙) — 분기나 변형이 없으므로 추출 비용이 낮고 추상화가 억지스럽지 않다.
- `OrderExecutionService`(시장가 체결)의 `CRYPTO_FEE_RATE`는 **공통화 대상에서 제외**한다 — 그쪽은 `STOCK_FEE_RATE`와 함께 시세 조회 시점의 `price`를 곱하는 별개의 계산(`priceOrder`)이라 "지정가 예약 재계산"과 목적이 다르다(시장가는 예약이 없다). 섞으면 오히려 두 도메인이 뒤엉킨다.

**설계**: `order.service` 패키지에 정적 유틸리티 클래스를 신설한다(서비스 빈이 아니다 — 상태가 없고 어떤 리포지토리도 필요 없으므로 `@Service`로 만들 이유가 없다).

```java
// 코인 지정가 예약·체결 금액(원금+수수료) 계산 — 생성·취소·체결·수정 네 서비스가 공유하는 유틸리티
package com.finplay.api.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class LimitOrderFeeCalculator {

    // 매직 넘버 금지 컨벤션 — spec.md 비즈니스 규칙(기존 코인 수수료율 ORD-004와 동일 재사용)
    public static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

    private LimitOrderFeeCalculator() {
    }

    // spec.md 비즈니스 규칙: 매수 예약현금 = FLOOR(수량×지정가) + FLOOR(FLOOR(수량×지정가)×0.05%)
    public static Reservation calculate(BigDecimal quantity, BigDecimal limitPrice) {
        long amount = quantity.multiply(limitPrice).setScale(0, RoundingMode.FLOOR).longValueExact();
        long fee = BigDecimal.valueOf(amount).multiply(CRYPTO_FEE_RATE)
            .setScale(0, RoundingMode.FLOOR).longValueExact();
        return new Reservation(amount, fee);
    }

    public record Reservation(long amount, long fee) {
        public long total() {
            return amount + fee;
        }
    }
}
```

**기존 3개 파일도 이 유틸리티를 쓰도록 함께 정리한다**(같은 커밋 — 새 유틸리티를 만들고 기존 호출부를 그대로 두면 다섯 번째 중복 경로가 열리는 것과 같으므로 즉시 치환한다):

- `LimitOrderCreationService`: `private static final CRYPTO_FEE_RATE`·`calculateCashRequired(...)` 사설 메서드를 제거하고, `createBuyOrder`에서 `long cashRequired = calculateCashRequired(quantity, limitPrice);`를 `long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();`로 교체.
- `LimitOrderCancelService`: `private static final CRYPTO_FEE_RATE`·인라인 `amount`/`fee` 계산 2줄을 `LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, limitPrice);`로 교체, 이후 `reservation.amount()`/`reservation.fee()`/`reservation.total()` 참조.
- `LimitOrderFillService`: 위와 동일하게 `fillIfPending`의 인라인 계산을 `LimitOrderFeeCalculator.calculate(...)` 호출로 교체.
- 세 파일 모두 `import java.math.RoundingMode;`가 더 이상 필요 없어지면 제거(컴파일 경고 방지).

## API 설계

### `PATCH /api/orders/{orderId}`

- 인증 필요. `Idempotency-Key` 헤더 불필요(LMT-003과 동일 이유 — 절대값 지정이라 자연 멱등).
- 성공 200, `LimitOrderResponse`(LMT-001 생성 응답과 동일 DTO 재사용 — 신규 DTO 아님, 갱신된 `orderId`·`quantity`·`limitPrice`·`status`(항상 `PENDING`)·`requestedAt`(불변)을 담아 반환).

**요청 (`LimitOrderModifyRequest`, 신규 DTO)**

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| limitPrice | BigDecimal | N | 지정 시 0보다 커야 함 |
| quantity | BigDecimal | N | 지정 시 0보다 커야 함, 소수점 8자리 이하 |

- 둘 다 `null`이면(요청 본문이 비어 있거나 두 필드 모두 생략) 400 `VALIDATION_ERROR`("변경할 값이 없습니다") — 컨트롤러 진입 직후, DB 조회 이전에 거부한다(리소스 상태와 무관한 요청 형식 검증이므로 존재/소유/상태 검증보다 먼저).
- 최종 `quantity`·`limitPrice`는 서비스에서 "요청에 있으면 요청값, 없으면 기존 주문값"으로 해석한 뒤, 그 최종값 기준으로 최소주문금액(코인 5,000원, `수량×지정가` 기준, ORD-003 재사용)을 재검증한다 — LMT-001 생성 시 검증과 동일 규칙이지만 이번엔 "부분 갱신 후 합성된 값"에 적용한다는 점이 다르다.

**오류 코드**

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | VALIDATION_ERROR | 요청 필드 둘 다 없음, 형식/범위 위반(수량·지정가 0 이하, 소수점 8자리 초과), 최종값 기준 최소주문금액 미달 |
| 404 | NOT_FOUND | `orderId`에 해당하는 주문 없음(LMT-003과 동일 코드 재사용) |
| 403 | FORBIDDEN | 주문의 `user` ≠ 요청자(LMT-003과 동일 코드 재사용) |
| 409 | ORDER_ALREADY_FILLED | 주문의 `status`가 이미 `FILLED`(LMT-003과 동일 코드 재사용) |
| 409 | ORDER_ALREADY_CANCELLED | 주문의 `status`가 이미 `CANCELLED`(LMT-003과 동일 코드 재사용) |
| 409 | INSUFFICIENT_CASH | 매수 재예약 시 예약 가능 현금 부족(LMT-001과 동일 코드 재사용) |
| 409 | INSUFFICIENT_QTY | 매도 재예약 시 예약 가능 수량 부족(LMT-001과 동일 코드 재사용) |

신규 `ErrorCode`는 추가하지 않는다 — 전부 기존 코드 재사용(이슈 #239 완료 조건).

## 데이터 모델

신규 테이블·컬럼·마이그레이션 없음(ADR-0004, spec.md "확정된 설계 결정" 12번 — 수정 이력 미보관 재확정). `orders.limit_price`·`orders.quantity`는 이미 nullable/필수 컬럼으로 존재해 갱신만 하면 된다.

### 엔티티 변경

**`Order`**: 수정 확정 메서드 추가(`cancel()`·`markFilled()`와 대칭 구조).

```java
public void modify(BigDecimal quantity, BigDecimal limitPrice) {
    if (this.status != OrderStatus.PENDING) {
        throw new IllegalStateException("PENDING 상태의 주문만 수정할 수 있습니다.");
    }
    this.quantity = quantity;
    this.limitPrice = limitPrice;
}
```

- 호출부(서비스 계층)가 항상 "요청에 없으면 기존값 유지"로 합성한 **최종값 두 개**를 넘긴다 — 엔티티는 부분 갱신을 모른다(부분 갱신 해석은 서비스 책임, 엔티티는 항상 완전한 상태 전이만 다룬다는 `cancel()`/`markFilled()`의 기존 관례와 동일).
- 서비스 계층은 `markFilled()`/`cancel()` 호출부와 동일한 관례로 **entity 메서드 호출 전에** `order.getStatus()`를 확인해 `BusinessException(ORDER_ALREADY_FILLED/CANCELLED)`를 던진다(entity의 `IllegalStateException`은 방어적 이중 체크, 정상 흐름에서는 도달하지 않는다).

`Account`·`Holding`은 변경 없음 — 기존 `reserveCash`/`releaseReservedCash`/`reserveQuantity`/`releaseReservedQuantity`를 그대로 재사용한다.

## 패키지·클래스 설계

| 클래스 | 패키지 | 역할 |
|---|---|---|
| `LimitOrderModifyRequest` | `order.dto.request` | 요청 DTO(`limitPrice`·`quantity` 둘 다 nullable) |
| `LimitOrderFeeCalculator` | `order.service` | 예약·체결 금액(원금+수수료) 계산 공용 유틸리티(신규, 위 "수수료 계산 공통화 결정") |
| `LimitOrderModifyService` | `order.service` | 수정 1건 처리(`LimitOrderCancelService`와 병렬 클래스 — 같은 잠금 순서·검증 순서, "해제 후 재예약"이 추가) |

`OrderController`(기존 파일)에 `@PatchMapping("/{orderId}")` 메서드를 추가한다 — 새 컨트롤러를 만들지 않는다. 기존 `@DeleteMapping("/{orderId}")`와 HTTP 메서드만 다르므로 경로 매핑 충돌 없음.

```java
@PatchMapping("/{orderId}")
public ResponseEntity<LimitOrderResponse> modifyLimitOrder(
    @AuthenticationPrincipal AuthenticatedUser principal,
    @PathVariable Long orderId,
    @RequestBody LimitOrderModifyRequest request) {
    return ResponseEntity.ok(limitOrderModifyService.modifyOrder(principal.userId(), orderId, request));
}
```

## 수정 흐름 (LMT-005)

`LimitOrderModifyService.modifyOrder(Long userId, Long orderId, LimitOrderModifyRequest request)`(`@Transactional`):

0. `request.limitPrice() == null && request.quantity() == null`이면 `BusinessException(VALIDATION_ERROR)` — DB 조회 이전, 요청 형식 검증.
1. `order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))` — **주문 락 + 존재(404) 검증**(LMT-003 `cancelOrder`와 동일 지점).
2. `!order.getUser().getId().equals(userId)`면 `BusinessException(ErrorCode.FORBIDDEN)` — **소유(403) 검증**.
3. `order.getStatus() == FILLED`면 `ORDER_ALREADY_FILLED`, `== CANCELLED`면 `ORDER_ALREADY_CANCELLED` — **상태(409) 검증**.
4. 최종값 합성: `finalQuantity = request.quantity() != null ? request.quantity() : order.getQuantity()`, `finalLimitPrice = request.limitPrice() != null ? request.limitPrice() : order.getLimitPrice()`.
5. 형식 재검증: `finalQuantity`·`finalLimitPrice` 0보다 큰지(요청에 지정된 필드만 — LMT-001의 `validateQuantityFormat`/`validateLimitPrice`와 동일 사설 메서드 재사용 또는 복제), 최종값 기준 최소주문금액(`finalQuantity × finalLimitPrice ≥ instrument.minOrderAmount`) — LMT-001의 `validateMinOrderAmount`와 동일 로직.
6. `account = accountService.getAccountByIdForUpdate(order.getAccount().getId())` — **계좌 락**(LMT-002·003과 동일한 두 번째 단계, BUY/SELL 공통으로 항상 잡는다).
7. `oldReservation = LimitOrderFeeCalculator.calculate(order.getQuantity(), order.getLimitPrice())`, `newReservation = LimitOrderFeeCalculator.calculate(finalQuantity, finalLimitPrice)`.
8. **BUY**:
   a. `account.releaseReservedCash(oldReservation.total())` — 변경 전 예약 해제.
   b. `account.getAvailableCash() < newReservation.total()`이면 `BusinessException(INSUFFICIENT_CASH)` — 해제 직후 늘어난 `availableCash` 기준으로 재예약 가능 여부 판정. 여기서 예외가 던져지면 `@Transactional`이 트랜잭션을 롤백해 (a)의 해제도 커밋되지 않는다(원자성의 핵심 — 명시적인 "롤백용 복구 코드"가 필요 없다. 구현 시 이 메서드 안에서 `saveAndFlush`·수동 `flush()`를 호출하지 않아야 이 보장이 유지된다).
   c. `account.reserveCash(newReservation.total())` — 변경 후 값으로 재예약.
9. **SELL**:
   a. `holding = portfolioSellService.getHoldingForUpdate(account, order.getInstrument())` — **holding 락**(세 번째 단계).
   b. `holding.releaseReservedQuantity(order.getQuantity())` — 변경 전 예약 수량 해제.
   c. `holding.getAvailableQuantity().compareTo(finalQuantity) < 0`이면 `BusinessException(INSUFFICIENT_QTY)` — 위 8-b와 동일한 원자성 근거.
   d. `holding.reserveQuantity(finalQuantity)` — 변경 후 수량으로 재예약.
10. `order.modify(finalQuantity, finalLimitPrice)`.
11. `LimitOrderResponse.from(order)` 반환.

**잠금 순서**(LMT-002·003과 동일, spec.md "확정된 설계 결정" 7·8번 재확인): `order → account → (SELL만) holding`.

**LMT-003과의 차이 요약**: 취소는 "해제만" 하고 끝나지만, 수정은 "해제 → 새 값으로 형식·최소금액 재검증 → 재예약"까지 한 트랜잭션에서 이어간다. 재예약 실패 시 롤백으로 원상 복구된다는 점이 이 기능의 존재 이유(spec.md "원자성")다.

## 잠금 순서 요약 (갱신)

| 흐름 | 순서 |
|---|---|
| LMT-001 BUY 생성 | account |
| LMT-001 SELL 생성 | holding |
| LMT-002 BUY 체결 | order → account → holding(있으면) |
| LMT-002 SELL 체결 | order → account → holding |
| LMT-003 취소 | order → account → (SELL만) holding |
| 시장가 SELL/BUY 체결(조정 후) | account → holding(있으면) |
| **LMT-005 수정** | **order → account → (SELL만) holding** |

LMT-005는 LMT-003과 동일한 순서를 그대로 따르므로 새로운 ABBA 사이클을 만들지 않는다.

## 동시성 테스트 시나리오 (`LimitOrderConcurrencyIntegrationTest`에 추가, 기존 `runConcurrently` 헬퍼 재사용)

spec.md 시나리오 21~25에 대응한다.

1. **정상 수정 단위 테스트**(`LimitOrderModifyServiceTest`, `@ExtendWith(MockitoExtension.class)`): BUY 수정 시 `releaseReservedCash`(변경 전 값)→`reserveCash`(변경 후 값) 호출 순서·인자 검증, SELL 수정 시 `releaseReservedQuantity`→`reserveQuantity` 호출 순서·인자 검증, 부분 갱신(한쪽만 요청 필드 존재) 시 나머지 필드가 기존 주문값으로 합성되는지, 둘 다 없으면 400, 존재하지 않는 주문 404, 타인 소유 403, 이미 `FILLED`/`CANCELLED` 409(개별 케이스), 검증 순서(존재→소유→상태→형식) 준수 여부.
2. **원자성 통합 테스트(spec.md 시나리오 23, 이 기능의 핵심 증명)**: `@SpringBootTest`(Testcontainers). 매수 지정가를 예약 가능 현금을 초과하도록 올려 `PATCH` 요청 → 409 `INSUFFICIENT_CASH` 응답 확인 **후**, `orderRepository.findById(orderId)`·`accountRepository.findById(accountId)`를 재조회해 `limitPrice`·`quantity`·`reservedCash`·`cashBalance`가 요청 전 값과 완전히 동일함을 DB 값으로 직접 대조한다(서비스 예외 타입만 확인하는 얕은 검증 금지 — spec.md LMT-005 완료 조건이 명시적으로 요구). 매도 초과 수량 `INSUFFICIENT_QTY` 버전도 동일 패턴으로 1개 더 추가.
3. **수정-대-체결 경합(spec.md 시나리오 24)**: `PENDING` 지정가 매수 주문에 대해 스레드 A는 `limitOrderModifyService.modifyOrder(...)`, 스레드 B는 `limitOrderFillService.fillIfPending(orderId)`를 동시에 호출한다 — 기존 `runConcurrently`(ready/start `CountDownLatch`) 헬퍼 재사용. 체결이 이기면 수정은 `ORDER_ALREADY_FILLED` 예외, 취소가 이기지 않으므로 이 시나리오는 "체결 승리"·"수정 승리"(수정이 이기면 체결 트리거가 이후 갱신된 `limitPrice`로 판정) 두 경로 모두 예약 이중 반환·이중 소비 없이 최종 `reservedCash`/`cashBalance`가 일관됨을 검증.
4. **수정-대-취소 경합(spec.md 시나리오 24 후반)**: 같은 패턴으로 스레드 A는 `modifyOrder`, 스레드 B는 `cancelOrder`를 동시 호출 — 한쪽만 성공(취소가 이기면 수정은 `ORDER_ALREADY_CANCELLED`, 수정이 이기면 취소는 갱신된 값 기준으로 정상 취소)하고 예약이 일관됨을 검증.

## 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7 + 규칙 10):

- `docs/api-routes.md`: 라우트 표에 `PATCH | /api/orders/{orderId} | order | ... | 015 LMT-005, Issue #239` 행 추가(기존 `DELETE /api/orders/{orderId}` 행 근처).
- `docs/api-contracts.md`: `## order` 절에 "지정가 주문 수정" 표 추가(요청 `LimitOrderModifyRequest`, 응답 `LimitOrderResponse` 재사용 명시, 오류 400/401/403/404/409 계약 — `INSUFFICIENT_CASH`/`INSUFFICIENT_QTY`/`ORDER_ALREADY_FILLED`/`ORDER_ALREADY_CANCELLED` 전부 기존 코드 재사용임을 명시).
- `docs/prd.md` §3 구현 현황 "지정가 주문·상시 체결(LMT-001~005)" 행을 이 PR 번호를 근거로 "완료"로 갱신한다 — LMT-001~005 전부 완료됨을 명시.

## 테스트 계획 (ADR-0003 기준)

- **단위(`LimitOrderModifyServiceTest`, 신규 파일)**: 위 "동시성 테스트 시나리오" 1번 그대로.
- **단위(`OrderTest`, 기존 파일)**: `modify()` 정상 전이(quantity·limitPrice 갱신 확인), `PENDING`이 아닌 상태에서 호출 시 `IllegalStateException`.
- **단위(`LimitOrderFeeCalculatorTest`, 신규 파일)**: `calculate(quantity, limitPrice)`가 기존 3개 서비스가 써오던 것과 동일한 `amount`/`fee`/`total()`을 반환하는지(회귀 방지 — 리팩터링이 계산 결과를 바꾸지 않았음을 증명하는 것이 핵심 목적), FLOOR 반올림 경계값(수수료가 정확히 나눠떨어지는 경우·나머지가 생기는 경우) 케이스.
- **슬라이스(`OrderControllerTest`, 기존 파일)**: `PATCH /api/orders/{orderId}` — 200 응답과 갱신된 필드 계약(`jsonPath`), 요청 본문이 비어있으면 400, 인증 실패 401, 404/403/409 오류 매핑.
- **통합(`LimitOrderConcurrencyIntegrationTest`, 기존 파일)**: 위 "동시성 테스트 시나리오" 2·3·4번.

## Decision Gate (spec.md "확정된 설계 결정" 12번 재확인, 변경 없음)

- 수정 이력은 저장하지 않는다 — 신규 컬럼·마이그레이션 없음(spec.md 12번 근거 참고).
- 수수료 계산은 공통 유틸리티(`LimitOrderFeeCalculator`)로 추출하고 기존 3개 서비스도 함께 정리한다(위 "수수료 계산 공통화 결정").
- 신규 오류 코드 없음 — 전부 LMT-001·LMT-003 코드 재사용.
