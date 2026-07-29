-- 주문·체결 원장 스키마(011-order-ledger-schema)의 5개 테이블을 일괄 생성한다. 금액은 원 단위 BIGINT, 수량은 DECIMAL(30,8) (PRD C-003).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE orders (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    user_id          BIGINT         NOT NULL,
    account_id       BIGINT         NOT NULL,
    instrument_id    BIGINT         NOT NULL,
    side             VARCHAR(10)    NOT NULL,
    order_type       VARCHAR(10)    NOT NULL,
    status           VARCHAR(10)    NOT NULL,
    quantity         DECIMAL(30,8)  NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    request_hash     CHAR(64)       NOT NULL,
    requested_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_orders_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

CREATE TABLE trades (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    order_id       BIGINT         NOT NULL,
    account_id     BIGINT         NOT NULL,
    instrument_id  BIGINT         NOT NULL,
    side           VARCHAR(10)    NOT NULL,
    price          DECIMAL(18,8)  NOT NULL,
    quantity       DECIMAL(30,8)  NOT NULL,
    amount         BIGINT         NOT NULL,
    fee            BIGINT         NOT NULL,
    realized_pnl   BIGINT         NULL,
    executed_at    DATETIME(6)    NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trades_order UNIQUE (order_id),
    CONSTRAINT fk_trades_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_trades_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_trades_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

CREATE TABLE holdings (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    account_id     BIGINT         NOT NULL,
    instrument_id  BIGINT         NOT NULL,
    quantity       DECIMAL(30,8)  NOT NULL,
    average_price  DECIMAL(18,8)  NOT NULL,
    is_active      BOOLEAN        NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_holdings_account_instrument UNIQUE (account_id, instrument_id),
    CONSTRAINT fk_holdings_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_holdings_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);

CREATE TABLE holding_lots (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    holding_id          BIGINT         NOT NULL,
    buy_trade_id        BIGINT         NOT NULL,
    original_quantity   DECIMAL(30,8)  NOT NULL,
    remaining_quantity  DECIMAL(30,8)  NOT NULL,
    unit_cost           DECIMAL(18,8)  NOT NULL,
    buy_fee             BIGINT         NOT NULL,
    executed_at         DATETIME(6)    NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_holding_lots_buy_trade UNIQUE (buy_trade_id),
    CONSTRAINT fk_holding_lots_holding FOREIGN KEY (holding_id) REFERENCES holdings (id),
    CONSTRAINT fk_holding_lots_buy_trade FOREIGN KEY (buy_trade_id) REFERENCES trades (id)
);

CREATE TABLE trade_allocations (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    sell_trade_id       BIGINT         NOT NULL,
    holding_lot_id      BIGINT         NOT NULL,
    allocated_quantity  DECIMAL(30,8)  NOT NULL,
    allocated_cost      BIGINT         NOT NULL,
    allocated_buy_fee   BIGINT         NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_trade_allocations_sell_trade FOREIGN KEY (sell_trade_id) REFERENCES trades (id),
    CONSTRAINT fk_trade_allocations_holding_lot FOREIGN KEY (holding_lot_id) REFERENCES holding_lots (id)
);
