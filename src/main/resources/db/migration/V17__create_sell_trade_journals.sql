-- 매도 체결 1건당 매도 회고 1건을 저장하는 sell_trade_journals 테이블을 생성한다(007-journal, JOUR-003).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE sell_trade_journals (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    sell_trade_id BIGINT        NOT NULL,
    content       VARCHAR(5000) NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sell_trade_journals_sell_trade UNIQUE (sell_trade_id),
    CONSTRAINT fk_sell_trade_journals_sell_trade
        FOREIGN KEY (sell_trade_id) REFERENCES trades (id)
);
