-- 일반·교육 공통 OCO 손절·익절 예약(021-general-risk-management-oco)의 plan·condition·멱등키 3개 테이블을 생성한다.
-- holding당 PENDING 1건 불변식은 부분 unique 인덱스가 아니라 앱 계층 검증이다 (021 plan.md). 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE exit_plans (
    id                     BIGINT         NOT NULL AUTO_INCREMENT,
    user_id                BIGINT         NOT NULL,
    holding_id             BIGINT         NOT NULL,
    intention_id           BIGINT         NULL,
    intention_instance_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    buy_trade_id           BIGINT         NULL,
    instrument_id          BIGINT         NOT NULL,
    quantity               DECIMAL(30,8)  NOT NULL,
    entry_price            DECIMAL(18,8)  NOT NULL,
    exit_price_type        VARCHAR(10)    NOT NULL,
    stop_loss_rate         DECIMAL(7,4)   NULL,
    take_profit_rate       DECIMAL(8,4)   NULL,
    stop_loss_price        DECIMAL(18,8)  NOT NULL,
    take_profit_price      DECIMAL(18,8)  NOT NULL,
    baseline_price         DECIMAL(18,8)  NOT NULL,
    baseline_observed_at   DATETIME(6)    NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    reserved_at            DATETIME(6)    NOT NULL,
    closed_at              DATETIME(6)    NULL,
    triggered_order_id     BIGINT         NULL,
    replay_session_id      BIGINT         NULL,
    request_hash           CHAR(64)       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exit_plans_user_intention_instance UNIQUE (user_id, intention_instance_key),
    CONSTRAINT fk_exit_plans_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_exit_plans_holding FOREIGN KEY (holding_id) REFERENCES holdings (id),
    CONSTRAINT fk_exit_plans_buy_trade FOREIGN KEY (buy_trade_id) REFERENCES trades (id),
    CONSTRAINT fk_exit_plans_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT fk_exit_plans_triggered_order FOREIGN KEY (triggered_order_id) REFERENCES orders (id),
    CONSTRAINT fk_exit_plans_replay_session FOREIGN KEY (replay_session_id) REFERENCES stock_replay_sessions (id),
    INDEX idx_exit_plans_holding_status (holding_id, status),
    INDEX idx_exit_plans_user_status (user_id, status)
);

CREATE TABLE exit_plan_conditions (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    exit_plan_id   BIGINT         NOT NULL,
    condition_type VARCHAR(20)    NOT NULL,
    trigger_price  DECIMAL(18,8)  NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exit_plan_conditions_plan_type UNIQUE (exit_plan_id, condition_type),
    CONSTRAINT fk_exit_plan_conditions_plan FOREIGN KEY (exit_plan_id) REFERENCES exit_plans (id)
);

CREATE TABLE exit_plan_idempotency_keys (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    exit_plan_id    BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exit_plan_idempotency_keys_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_exit_plan_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_exit_plan_idempotency_keys_plan FOREIGN KEY (exit_plan_id) REFERENCES exit_plans (id)
);
