-- 튜토리얼 실행 세대의 영속 attempt·위험 스냅샷과 주문 귀속 컬럼을 추가한다(039 TUTORIAL-FLOW-001).
-- 기존 주문은 두 귀속 컬럼이 모두 NULL이며, 추가형 변경만 사용해 구버전 앱과의 호환성을 유지한다(ADR-0004).

CREATE TABLE practice_attempts (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    market            VARCHAR(20)  NOT NULL,
    run_number        BIGINT       NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    instrument_id     BIGINT       NULL,
    anchor_at         DATETIME(6)  NULL,
    tutorial_date     DATE         NULL,
    price_seed        BIGINT       NULL,
    generator_version SMALLINT     NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    completed_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_attempts_user_market UNIQUE (user_id, market),
    CONSTRAINT fk_practice_attempts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_attempts_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT chk_practice_attempts_market CHECK (market IN ('STOCK', 'CRYPTO')),
    CONSTRAINT chk_practice_attempts_run_number CHECK (run_number > 0),
    CONSTRAINT chk_practice_attempts_status CHECK (
        status IN ('SELECTING_INSTRUMENT', 'IN_PROGRESS', 'EXPIRED', 'COMPLETED')
    ),
    CONSTRAINT chk_practice_attempts_state_fields CHECK (
        (status = 'SELECTING_INSTRUMENT' AND instrument_id IS NULL AND anchor_at IS NULL AND tutorial_date IS NULL
            AND price_seed IS NULL AND generator_version IS NULL)
        OR
        (status IN ('IN_PROGRESS', 'EXPIRED', 'COMPLETED') AND instrument_id IS NOT NULL
            AND anchor_at IS NOT NULL AND tutorial_date IS NOT NULL
            AND price_seed IS NOT NULL AND generator_version IS NOT NULL AND generator_version > 0)
    ),
    CONSTRAINT chk_practice_attempts_completion_time CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE TABLE practice_risk_snapshots (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    attempt_id        BIGINT         NOT NULL,
    run_number        BIGINT         NOT NULL,
    buy_trade_id      BIGINT         NOT NULL,
    entry_price       DECIMAL(18,8)  NOT NULL,
    stop_loss_price   DECIMAL(18,8)  NOT NULL,
    take_profit_price DECIMAL(18,8)  NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_risk_snapshots_attempt_run UNIQUE (attempt_id, run_number),
    CONSTRAINT fk_practice_risk_snapshots_attempt FOREIGN KEY (attempt_id) REFERENCES practice_attempts (id),
    CONSTRAINT fk_practice_risk_snapshots_buy_trade FOREIGN KEY (buy_trade_id) REFERENCES trades (id),
    CONSTRAINT chk_practice_risk_snapshots_run_number CHECK (run_number > 0),
    CONSTRAINT chk_practice_risk_snapshots_entry_price CHECK (entry_price > 0),
    CONSTRAINT chk_practice_risk_snapshots_stop_loss_price CHECK (stop_loss_price > 0),
    CONSTRAINT chk_practice_risk_snapshots_take_profit_price CHECK (take_profit_price > 0),
    CONSTRAINT chk_practice_risk_snapshots_price_order CHECK (
        stop_loss_price < entry_price AND entry_price < take_profit_price
    )
);

ALTER TABLE orders
    ADD COLUMN practice_attempt_id BIGINT NULL AFTER practice_price_session_id,
    ADD COLUMN practice_attempt_run_number BIGINT NULL AFTER practice_attempt_id;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_practice_attempt FOREIGN KEY (practice_attempt_id) REFERENCES practice_attempts (id),
    ADD CONSTRAINT chk_orders_practice_attempt_attribution CHECK (
        (practice_attempt_id IS NULL AND practice_attempt_run_number IS NULL)
        OR (practice_attempt_id IS NOT NULL AND practice_attempt_run_number IS NOT NULL
            AND practice_attempt_run_number > 0)
    );

ALTER TABLE orders
    ADD INDEX idx_orders_practice_attempt_run_status
        (practice_attempt_id, practice_attempt_run_number, status, id);
