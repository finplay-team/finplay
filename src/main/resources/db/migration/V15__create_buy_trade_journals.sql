-- 매수 체결 1건당 투자일기 1건을 저장하는 buy_trade_journals 테이블을 생성한다(007-journal, JOUR-001).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE buy_trade_journals (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    buy_trade_id  BIGINT        NOT NULL,
    content       VARCHAR(5000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_buy_trade_journals_buy_trade UNIQUE (buy_trade_id),
    CONSTRAINT fk_buy_trade_journals_buy_trade
        FOREIGN KEY (buy_trade_id) REFERENCES trades (id)
);
