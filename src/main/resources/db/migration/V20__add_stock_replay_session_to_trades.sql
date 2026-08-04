-- 주식 체결이 발생한 재생세션을 기록하고 코인 체결에는 NULL을 유지한다.

ALTER TABLE trades
    ADD COLUMN stock_replay_session_id BIGINT NULL AFTER instrument_id,
    ADD CONSTRAINT fk_trades_stock_replay_session
        FOREIGN KEY (stock_replay_session_id) REFERENCES stock_replay_sessions (id);
