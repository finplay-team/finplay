-- KIS 과거 분봉 수집 시도 이력(성공·부분성공·전체실패·중복스킵)을 기록하는 정본 테이블을 생성한다.
-- 저장에 실패해 stock_candles에 행이 없는 경우(FAILED)도 이 테이블에는 남는다 (spec.md MKT-005).
-- source_trading_date는 UNIQUE가 아니다 — 같은 거래일에 여러 번 수집 시도(재실행)가 있을 수 있고,
-- 재수집 판정(SUCCESS/PARTIAL_SUCCESS 존재 여부 비교)은 이슈 #83 범위이므로 이 마이그레이션에서는 조회용 인덱스만 둔다.
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE market_data_imports (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    source               VARCHAR(50)  NOT NULL,
    source_trading_date  DATE         NOT NULL,
    collected_at         DATETIME(6)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    failure_reason       VARCHAR(500) NULL,
    PRIMARY KEY (id),
    INDEX idx_market_data_imports_source_trading_date (source_trading_date)
);
