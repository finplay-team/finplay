-- 주식 1분봉 정본 테이블을 생성한다. validation_status 컬럼은 두지 않는다 — 검증 통과분만 저장되어 죽은 컬럼이 되기 때문 (spec MKT-005).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE stock_candles (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT        NOT NULL,
    trading_date  DATE          NOT NULL,
    candle_time   TIME          NOT NULL,
    open          DECIMAL(18,4) NOT NULL,
    high          DECIMAL(18,4) NOT NULL,
    low           DECIMAL(18,4) NOT NULL,
    close         DECIMAL(18,4) NOT NULL,
    volume        BIGINT        NOT NULL,
    data_source   VARCHAR(50)   NOT NULL,
    collected_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_candles_instrument_date_time UNIQUE (instrument_id, trading_date, candle_time),
    CONSTRAINT fk_stock_candles_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id)
);
