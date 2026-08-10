-- 코인 튜토리얼 가상 가격 세션(사용자·종목별 결정적 시계열 cursor)을 영속하는 테이블을 생성한다.

CREATE TABLE practice_price_sessions (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    instrument_id     BIGINT        NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    seed              BIGINT        NOT NULL,
    generator_version SMALLINT      NOT NULL,
    start_price       DECIMAL(18,8) NOT NULL,
    current_tick      SMALLINT      NOT NULL,
    current_price     DECIMAL(18,8) NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    completed_at      DATETIME(6)   NULL,
    active_slot       TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_price_sessions_user_instrument_active UNIQUE (user_id, instrument_id, active_slot),
    CONSTRAINT fk_practice_price_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_price_sessions_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT chk_practice_price_sessions_status CHECK (status IN ('ACTIVE', 'COMPLETED')),
    CONSTRAINT chk_practice_price_sessions_tick CHECK (current_tick BETWEEN 0 AND 99),
    CONSTRAINT chk_practice_price_sessions_start_price CHECK (start_price > 0),
    CONSTRAINT chk_practice_price_sessions_current_price CHECK (current_price > 0)
);
