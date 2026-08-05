-- 코인 지정가 매매(015-limit-order)를 위한 예약 원장 컬럼과 체결 후보 인덱스를 추가한다. 머지된 마이그레이션은 수정 금지 (ADR-0004).

ALTER TABLE accounts
    ADD COLUMN reserved_cash BIGINT NOT NULL DEFAULT 0 AFTER cash_balance;

ALTER TABLE holdings
    ADD COLUMN reserved_quantity DECIMAL(30,8) NOT NULL DEFAULT 0 AFTER quantity;

ALTER TABLE orders
    ADD COLUMN limit_price DECIMAL(18,8) NULL AFTER quantity;

ALTER TABLE orders
    ADD INDEX idx_orders_limit_fill (instrument_id, status, side, limit_price);
