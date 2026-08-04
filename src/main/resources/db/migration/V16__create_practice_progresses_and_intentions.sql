-- 투자 실습 진행 상태와 매수 전 사전 의도 기록 테이블을 생성한다.

CREATE TABLE practice_progresses (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    tutorial_key VARCHAR(50)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    started_at   DATETIME(6)  NOT NULL,
    completed_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_practice_progresses_user_tutorial UNIQUE (user_id, tutorial_key),
    CONSTRAINT fk_practice_progresses_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE practice_intentions (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    user_id       BIGINT         NOT NULL,
    instrument_id BIGINT         NOT NULL,
    quantity      DECIMAL(30, 8) NOT NULL,
    stop_loss     DECIMAL(18, 8) NOT NULL,
    take_profit   DECIMAL(18, 8) NOT NULL,
    created_at    DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_practice_intentions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_practice_intentions_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);
