-- sell_trade_journals에 수정시각 컬럼을 추가한다(007-journal, JOUR-004). 머지된 마이그레이션은 수정 금지 (ADR-0004).

ALTER TABLE sell_trade_journals
    ADD COLUMN updated_at DATETIME(6) NULL;

UPDATE sell_trade_journals
    SET updated_at = created_at
    WHERE updated_at IS NULL;

ALTER TABLE sell_trade_journals
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;
