# Plan: 주문·체결 원장 스키마 구축

## 관련 문서

- Spec: `./spec.md`
- 참고 spec (컬럼 요구 근거): `ai/specs/004-order-buy/spec.md`, `ai/specs/005-order-sell/spec.md`
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md) (도메인 패키지 구조, 도메인 간 참조는 service 레이어만), [ADR-0004](../../adr/0004-flyway-migrations.md) (Flyway로만 스키마 변경, 머지된 마이그레이션 수정 금지)
- PRD 근거: §6 1차 데이터 모델(`orders`·`trades`·`holdings`·`holding_lots`·`trade_allocations` 행과 핵심 제약), §7 구조(`order`/`portfolio` 도메인 책임 분리), C-003(금액과 원장)

## API 설계

없음 — 이 spec은 API를 추가하지 않는다 (이슈 #12 범위: 데이터 구조 선행 작업).

## 입력 명세

없음 — Repository는 `JpaRepository` 상속만 두고 쿼리 메서드를 추가하지 않으므로 검증 대상 입력이 없다.

## 패키지 구조 결정

PRD §7 구조는 도메인 책임을 다음과 같이 이미 나눠놓았다.

- `order`: "시장가 검증·체결·**멱등성**·수수료" — 주문 요청과 체결 원장.
- `portfolio`: "보유·**FIFO lot**·거래내역·평가손익·합산 요약" — 보유수량과 lot 소비.

이 문서에 맞춰 5개 엔티티를 하나의 `order` 패키지에 몰아넣지 않고 두 도메인으로 나눈다.

| 엔티티 | 패키지 | 근거 |
|---|---|---|
| `Order` | `com.finplay.api.order.domain` | 주문 요청·멱등성의 소유자 |
| `Trade` | `com.finplay.api.order.domain` | "체결"은 PRD가 명시적으로 `order` 책임으로 분류 |
| `Holding` | `com.finplay.api.portfolio.domain` | "보유"는 PRD가 명시적으로 `portfolio` 책임으로 분류 |
| `HoldingLot` | `com.finplay.api.portfolio.domain` | "FIFO lot"은 PRD가 명시적으로 `portfolio` 책임으로 분류 |
| `TradeAllocation` | `com.finplay.api.portfolio.domain` | `HoldingLot` 소비 기록이라 `HoldingLot`과 같은 도메인에 둔다 |
| `OrderSide`/`OrderType`/`OrderStatus` | `com.finplay.api.order.domain` | `Order`에 종속된 열거형 |

**엔티티 간 참조**: 이 코드베이스는 이미 cross-domain `@ManyToOne` 전례가 있다 (`account.domain.Account.user` → `auth.domain.User`, `Account.market`/`Instrument.market`은 도메인별로 각자 별도 `Market` enum을 복제해 참조하지 않는 방식). 이번 5개 엔티티도 같은 전례를 따른다 — `Order`가 `account.domain.Account`·`market.domain.Instrument`를 `@ManyToOne`으로 직접 참조하고, `TradeAllocation`(portfolio)이 `Trade`(order)를 `@ManyToOne`으로 직접 참조한다. ADR-0002가 금지하는 것은 **service 계층의 cross-domain repository 주입**이지 엔티티 관계가 아니다.

**후속 이슈에 대한 함의(참고용, 이 spec의 범위는 아님)**: #13(매수)·#41(매도)의 서비스 로직이 `Holding`/`HoldingLot`을 갱신하려면 `order` 도메인 서비스가 `portfolio` 도메인 서비스를 같은 트랜잭션 안에서 호출해야 한다(ADR-0002 — repository 직접 주입 금지). 이는 004/005 plan.md가 착수 시점에 결정할 사항이며, 이 문서는 그 전제가 되는 패키지 배치 근거만 남긴다.

기존 `Market` enum(`account.domain.Market`, `market.domain.Market`)은 재사용하지 않는다 — `orders`/`trades`/`holdings` 테이블에 `market` 컬럼을 별도로 두지 않고 `account_id`·`instrument_id` FK로만 시장을 특정한다. "다른 시장 계좌·종목 조합 요청 거부"(PRD ORD-003)는 서비스 계층 검증 대상이며 스키마 책임이 아니다.

## 데이터 모델

공통: PK는 전부 `BIGINT AUTO_INCREMENT`, 타임스탬프는 `DATETIME(6)`. 금액(원 단위 총액·수수료·실현손익)은 `BIGINT`, 수량은 `DECIMAL(30,8)`(코인 소수점 8자리 요구를 만족하며 정수인 주식 수량도 같은 컬럼에 담는다 — 정수 검증은 서비스 계층 책임, PRD ORD-003). 가격류(체결가·평균단가·lot 단가)는 `instruments.tick_size`와 같은 정밀도인 `DECIMAL(18,8)`을 쓴다 — 코인은 1원 미만 tick(XRP·DOGE 등 tick 0.1)이 실재하므로 원 단위 `BIGINT`로는 체결가를 표현할 수 없다. 금액·수량 계산에 `double`·`float`는 쓰지 않는다(PRD C-003).

### V10 마이그레이션 — 테이블별 컬럼

#### `orders`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| user_id | BIGINT | NOT NULL, FK → users(id) | 멱등키 유니크 판정 주체 (계좌가 아닌 회원 단위) |
| account_id | BIGINT | NOT NULL, FK → accounts(id) | 시장별 계좌 |
| instrument_id | BIGINT | NOT NULL, FK → instruments(id) | 대상 종목 |
| side | VARCHAR(10) | NOT NULL | `OrderSide` — BUY/SELL |
| order_type | VARCHAR(10) | NOT NULL | `OrderType` — MARKET |
| status | VARCHAR(10) | NOT NULL | `OrderStatus` — FILLED |
| quantity | DECIMAL(30,8) | NOT NULL | 요청수량 |
| idempotency_key | VARCHAR(100) | NOT NULL | 클라이언트 `Idempotency-Key` 헤더 값 |
| request_hash | CHAR(64) | NOT NULL | 요청 본문 해시(SHA-256 hex) — 같은 키·다른 본문 감지용(#22, `IDEMPOTENCY_CONFLICT`) |
| requested_at | DATETIME(6) | NOT NULL | 주문 요청시각 (PORT-003 `requestedAt`) |

제약: `CONSTRAINT uk_orders_user_idempotency UNIQUE (user_id, idempotency_key)` (수용기준·이슈 #22 선행조건), `fk_orders_user`, `fk_orders_account`, `fk_orders_instrument`.

#### `trades`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| order_id | BIGINT | NOT NULL, FK → orders(id) | 체결의 근거 주문 |
| account_id | BIGINT | NOT NULL, FK → accounts(id) | 거래내역 조회 시 조인 없이 시장별 필터링(PORT-002) |
| instrument_id | BIGINT | NOT NULL, FK → instruments(id) | 위와 동일 목적 |
| side | VARCHAR(10) | NOT NULL | 주문과 동일 — 목록 조회에서 order 조인 회피 |
| price | DECIMAL(18,8) | NOT NULL | 체결가 |
| quantity | DECIMAL(30,8) | NOT NULL | 체결수량 |
| amount | BIGINT | NOT NULL | 거래금액(원 단위, = price × quantity를 서비스 계층에서 계산) |
| fee | BIGINT | NOT NULL | 수수료(원 미만 내림, 서비스 계층 계산) |
| realized_pnl | BIGINT | NULL | 매도 체결만 값을 가짐(FIFO 실현손익). 매수 체결은 NULL |
| executed_at | DATETIME(6) | NOT NULL | 체결시각 — FIFO 정렬 기준(ORD-005 "실행시각이 가장 빠른") |
| created_at | DATETIME(6) | NOT NULL | 행 생성시각(감사용, 통상 executed_at과 동일) |

제약: `CONSTRAINT uk_trades_order UNIQUE (order_id)` — 1차는 시장가 즉시 전량체결만 지원해 주문 1건당 체결 1건이 항상 성립한다(부분체결은 1차 명시적 제외). 이 제약은 그 불변식을 DB 레벨에서 강제한다. 부분체결이 2차에 도입되면 새 마이그레이션으로 제거해야 한다(ADR-0004). `fk_trades_order`, `fk_trades_account`, `fk_trades_instrument`.

#### `holdings`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| account_id | BIGINT | NOT NULL, FK → accounts(id) | |
| instrument_id | BIGINT | NOT NULL, FK → instruments(id) | |
| quantity | DECIMAL(30,8) | NOT NULL | 현재 보유수량 |
| average_price | DECIMAL(18,8) | NOT NULL | 평균단가 |
| is_active | BOOLEAN | NOT NULL | 전량 매도 시 `false` — 행을 삭제하지 않고 비활성화(PORT-001 "활성 보유목록에서 제거", 005 spec "활성/비활성"). `UNIQUE(account_id, instrument_id)` 때문에 삭제 후 재매수 시 재생성이 아니라 같은 행 재활성화가 필요 |
| created_at | DATETIME(6) | NOT NULL | |
| updated_at | DATETIME(6) | NOT NULL | 매수·매도마다 갱신 |

제약: `CONSTRAINT uk_holdings_account_instrument UNIQUE (account_id, instrument_id)` (수용기준), `fk_holdings_account`, `fk_holdings_instrument`.

#### `holding_lots`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| holding_id | BIGINT | NOT NULL, FK → holdings(id) | 소속 보유(계좌·종목) |
| buy_trade_id | BIGINT | NOT NULL, FK → trades(id) | 이 lot을 만든 매수 체결 |
| original_quantity | DECIMAL(30,8) | NOT NULL | 최초수량(불변) |
| remaining_quantity | DECIMAL(30,8) | NOT NULL | 잔여수량(배분마다 감소) |
| unit_cost | DECIMAL(18,8) | NOT NULL | 매수단가(= 매수 체결가) |
| buy_fee | BIGINT | NOT NULL | 매수수수료 총액(배분 시 비율 배분의 원천) |
| executed_at | DATETIME(6) | NOT NULL | 매수 체결시각 — FIFO 소비 순서 기준 |
| created_at | DATETIME(6) | NOT NULL | |

제약: `CONSTRAINT uk_holding_lots_buy_trade UNIQUE (buy_trade_id)` — "매수 체결 1건 = lot 1건"(spec 비즈니스 규칙)을 DB 레벨에서 강제. `fk_holding_lots_holding`, `fk_holding_lots_buy_trade`.

#### `trade_allocations`

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | |
| sell_trade_id | BIGINT | NOT NULL, FK → trades(id) | 배분을 발생시킨 매도 체결 |
| holding_lot_id | BIGINT | NOT NULL, FK → holding_lots(id) | 소비된 매수 lot |
| allocated_quantity | DECIMAL(30,8) | NOT NULL | 이 lot에서 소비된 수량 |
| allocated_cost | BIGINT | NOT NULL | 배분된 매수원가(원 단위) |
| allocated_buy_fee | BIGINT | NOT NULL | 배분된 매수수수료(원 단위) — 매수원가와 분리해 005 spec의 "실현손익 = (매도금액-매도수수료) - (배분 매수원가+배분 매수수수료)" 계산식을 그대로 컬럼화 |
| created_at | DATETIME(6) | NOT NULL | |

제약: `fk_trade_allocations_sell_trade`, `fk_trade_allocations_holding_lot`. (수용기준에 명시된 유니크 제약 2종에 포함되지 않아 추가 유니크는 두지 않는다.)

### 열거형 3종 (`com.finplay.api.order.domain`)

- `OrderSide`: `BUY`, `SELL`
- `OrderType`: `MARKET` — 1차 유일 지원 유형(ORD-001). 지정가는 값 자체를 추가하지 않는다(추측 구현 금지, C-002) — 지정가 요청 거부(`UNSUPPORTED_ORDER_TYPE`)는 #13 요청 DTO 검증 책임이며 이 enum이 표현할 값이 아니다.
- `OrderStatus`: `FILLED` — 1차는 검증 실패 시 주문 자체를 남기지 않고(spec 비즈니스 규칙), 시장가는 즉시 전량 체결만 존재하므로(PORT-003) 성공 케이스 값 하나만 둔다. 취소·부분체결·미체결 상태는 1차 제외 범위(이슈 #12 제외 범위)라 값을 미리 만들지 않는다.

### 엔티티 필드 설계

Lombok 규칙(`docs/conventions.md`): `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, private 생성자 + `static create(...)` 팩토리, setter 없음 — `Account`/`Instrument`와 동일.

- **`Order`** (`order.domain`): `id`, `user`(`@ManyToOne` → `auth.domain.User`), `account`(`@ManyToOne` → `account.domain.Account`), `instrument`(`@ManyToOne` → `market.domain.Instrument`), `side`(`OrderSide`), `orderType`(`OrderType`), `status`(`OrderStatus`), `quantity`(`BigDecimal`), `idempotencyKey`(`String`), `requestHash`(`String`), `requestedAt`(`LocalDateTime`). 팩토리: `Order.create(user, account, instrument, side, orderType, quantity, idempotencyKey, requestHash, now)` — `status`는 팩토리 내부에서 `FILLED`로 고정(1차는 즉시 체결만 존재).
- **`Trade`** (`order.domain`): `id`, `order`(`@ManyToOne` → `Order`), `account`, `instrument`(위와 동일 참조), `side`(`OrderSide`), `price`(`BigDecimal`), `quantity`(`BigDecimal`), `amount`(`long`), `fee`(`long`), `realizedPnl`(`Long`, nullable), `executedAt`, `createdAt`. 팩토리: 매수용 `Trade.createBuy(...)`(realizedPnl 없이 생성), 매도용 `Trade.createSell(..., realizedPnl)` — 또는 공통 `Trade.of(...)` 하나로 두고 `realizedPnl`을 nullable 파라미터로 받는 방식 중 구현 단계(#13/#41)에서 결정. 이 spec은 컬럼·필드 존재만 확정한다.
- **`Holding`** (`portfolio.domain`): `id`, `account`, `instrument`, `quantity`(`BigDecimal`), `averagePrice`(`BigDecimal`), `isActive`(`boolean`), `createdAt`, `updatedAt`. 팩토리: `Holding.create(account, instrument, now)` — `quantity=0`, `averagePrice=0`, `isActive=false`로 시작(최초 매수 체결 시 갱신은 #13 책임). 상태 변경은 `fill(...)` 같은 의도 노출 메서드로(컨벤션) — 구체 메서드는 #13/#41에서 추가.
- **`HoldingLot`** (`portfolio.domain`): `id`, `holding`(`@ManyToOne` → `Holding`), `buyTrade`(`@ManyToOne` → `order.domain.Trade`), `originalQuantity`(`BigDecimal`), `remainingQuantity`(`BigDecimal`), `unitCost`(`BigDecimal`), `buyFee`(`long`), `executedAt`, `createdAt`. 팩토리: `HoldingLot.create(holding, buyTrade, quantity, unitCost, buyFee, executedAt, now)` — `originalQuantity = remainingQuantity = quantity`.
- **`TradeAllocation`** (`portfolio.domain`): `id`, `sellTrade`(`@ManyToOne` → `order.domain.Trade`), `holdingLot`(`@ManyToOne` → `HoldingLot`), `allocatedQuantity`(`BigDecimal`), `allocatedCost`(`long`), `allocatedBuyFee`(`long`), `createdAt`. 팩토리: `TradeAllocation.create(sellTrade, holdingLot, allocatedQuantity, allocatedCost, allocatedBuyFee, now)`.

### Repository 5종

`JpaRepository<Entity, Long>` 상속만 둔다 (`AccountRepository`와 동일 최소 패턴). 조회 쿼리 메서드는 이 spec 범위가 아니다.

- `order.repository.OrderRepository extends JpaRepository<Order, Long>`
- `order.repository.TradeRepository extends JpaRepository<Trade, Long>`
- `portfolio.repository.HoldingRepository extends JpaRepository<Holding, Long>`
- `portfolio.repository.HoldingLotRepository extends JpaRepository<HoldingLot, Long>`
- `portfolio.repository.TradeAllocationRepository extends JpaRepository<TradeAllocation, Long>`

## 테스트 계획

- 단위: 해당 없음 (서비스 로직 없음).
- 슬라이스: `@DataJpaTest` 1개(예: `order.domain.OrderLedgerSchemaTest` 또는 도메인별 분리) —
  - 5개 엔티티를 각각 저장·조회해 엔티티-스키마 매핑을 검증한다.
  - `UNIQUE(orders.user_id, orders.idempotency_key)` 위반 시 `DataIntegrityViolationException`(또는 하위 예외) 발생을 검증한다.
  - `UNIQUE(holdings.account_id, holdings.instrument_id)` 위반 시 동일하게 검증한다.
- 통합: 해당 없음 — API가 없어 Testcontainers 통합 시나리오 테스트 대상이 없다(ADR-0003 기준 슬라이스 테스트로 충분).
