# Plan: 코인 지정가 매매 — 생성·체결·취소 (LMT-001~003)

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
