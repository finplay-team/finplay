-- 주식 일봉(장기 아카이브, 최근 3년) 테이블을 생성한다. stock_candles(1분봉, 20영업일 롤링)와 보관 정책이
-- 정반대라 별도 테이블로 둔다 — 1분봉 정리 배치(이슈 #83)가 이 테이블을 건드리지 않는다 (spec 050, STOCK-DAILY-005).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE stock_daily_candles (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT        NOT NULL,
    trading_date  DATE          NOT NULL,
    open          DECIMAL(18,4) NOT NULL,
    high          DECIMAL(18,4) NOT NULL,
    low           DECIMAL(18,4) NOT NULL,
    close         DECIMAL(18,4) NOT NULL,
    volume        BIGINT        NOT NULL,
    data_source   VARCHAR(50)   NOT NULL,
    collected_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_daily_candles_instrument_date UNIQUE (instrument_id, trading_date),
    CONSTRAINT fk_stock_daily_candles_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);
