-- 튜토리얼 전용 샘플 종목 플래그 컬럼과 시장별 샘플 종목 3개씩(주식·코인) 시드 데이터를 추가한다.
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

ALTER TABLE instruments ADD COLUMN is_tutorial_sample BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO instruments (market, symbol, name, tick_size, min_order_amount, tradable, is_tutorial_sample, created_at) VALUES
('STOCK', 'SANDBOX_STK_1', '연습용 주식 A', 100, 10000, TRUE,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', 'SANDBOX_STK_2', '연습용 주식 B', 100, 10000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', 'SANDBOX_STK_3', '연습용 주식 C', 100, 10000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_1', '연습용 코인 A', 1, 5000, TRUE,  TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_2', '연습용 코인 B', 1, 5000, FALSE, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SANDBOX_COIN_3', '연습용 코인 C', 1, 5000, FALSE, TRUE, CURRENT_TIMESTAMP(6));
