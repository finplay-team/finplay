-- 시장가/지정가 매매 기반 실습 3단계의 가격 관찰과 자유 복기를 영속하는 테이블을 생성한다.

CREATE TABLE practice_market_observations (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    user_id             BIGINT         NOT NULL,
    holding_id          BIGINT         NOT NULL,
    instrument_id       BIGINT         NOT NULL,
    current_price       DECIMAL(18, 8) NOT NULL,
    closer_to_boundary  BOOLEAN        NULL,
    closer_boundary     VARCHAR(16)    NULL,
    evidence_type       VARCHAR(20)    NULL,
    observed_at         DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_practice_market_observations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_market_observations_holding FOREIGN KEY (holding_id) REFERENCES holdings (id),
    INDEX idx_practice_market_observations_user_holding_observed (user_id, holding_id, observed_at)
);

CREATE TABLE practice_market_reflections (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    user_id        BIGINT         NOT NULL,
    holding_id     BIGINT         NOT NULL,
    tutorial_key   VARCHAR(50)    NOT NULL,
    prompt_version SMALLINT       NOT NULL DEFAULT 1,
    answer         VARCHAR(2000)  NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_market_reflections_user_tutorial UNIQUE (user_id, tutorial_key),
    CONSTRAINT fk_practice_market_reflections_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_market_reflections_holding FOREIGN KEY (holding_id) REFERENCES holdings (id)
);
