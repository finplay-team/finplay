-- 교육 전용 지정가 BUY 주문을 코인 가상 가격 세션에 귀속시키는 FK 컬럼과 세션 스코프 조회 인덱스를 추가한다. 기존 행은 NULL이며 백필하지 않는다.

ALTER TABLE orders
    ADD COLUMN practice_price_session_id BIGINT NULL AFTER limit_price;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_practice_price_session FOREIGN KEY (practice_price_session_id) REFERENCES practice_price_sessions (id);

ALTER TABLE orders
    ADD INDEX idx_orders_practice_price_session (practice_price_session_id, status, id);
