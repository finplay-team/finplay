-- 종목 정본 테이블과 임시 시드 데이터(주식 16종·코인 12종)를 생성한다.
-- 주의: 실제 프론트엔드 종목 리스트를 확인할 수 없어 임시 시드를 사용한다 (사용자 승인, 2026-07-28 확인) — 실제 리스트 확정 시 새 마이그레이션으로 교체 예정.
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE instruments (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    market           VARCHAR(20)   NOT NULL,
    symbol           VARCHAR(20)   NOT NULL,
    name             VARCHAR(100)  NOT NULL,
    tick_size        DECIMAL(18,8) NOT NULL,
    min_order_amount BIGINT        NOT NULL,
    tradable         BOOLEAN       NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instruments_symbol UNIQUE (symbol)
);

-- 주식 16종 (KOSPI 시가총액 상위, KRX 호가단위 규정에 맞춘 근사 tick_size, min_order_amount는 1주 매수 가능 근사 금액)
INSERT INTO instruments (market, symbol, name, tick_size, min_order_amount, tradable, created_at) VALUES
('STOCK', '005930', '삼성전자',          100, 70000,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '000660', 'SK하이닉스',        500, 180000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '373220', 'LG에너지솔루션',    500, 350000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '207940', '삼성바이오로직스', 1000, 800000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '005380', '현대차',            500, 230000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '000270', '기아',              100, 95000,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '068270', '셀트리온',          500, 180000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '035420', 'NAVER',             500, 180000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '005490', 'POSCO홀딩스',       500, 380000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '006400', '삼성SDI',           500, 350000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '051910', 'LG화학',            500, 350000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '105560', 'KB금융',            100, 75000,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '055550', '신한지주',          50,  48000,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '035720', '카카오',            50,  40000,  TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '012330', '현대모비스',        500, 230000, TRUE, CURRENT_TIMESTAMP(6)),
('STOCK', '096770', 'SK이노베이션',      500, 110000, TRUE, CURRENT_TIMESTAMP(6));

-- 코인 12종 (빗썸 KRW 마켓 거래량 상위, tick_size는 원화 가격대별 근사치, min_order_amount는 빗썸 최소주문금액 관례에 맞춰 5,000원으로 통일)
INSERT INTO instruments (market, symbol, name, tick_size, min_order_amount, tradable, created_at) VALUES
('CRYPTO', 'BTC',  '비트코인',        1000, 5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'ETH',  '이더리움',        1000, 5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'XRP',  '리플',            0.1,  5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'SOL',  '솔라나',          50,   5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'DOGE', '도지코인',        0.1,  5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'ADA',  '에이다',          0.1,  5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'TRX',  '트론',            0.1,  5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'AVAX', '아발란체',        10,   5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'LINK', '체인링크',        10,   5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'DOT',  '폴카닷',          1,    5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'BCH',  '비트코인캐시',    50,   5000, TRUE, CURRENT_TIMESTAMP(6)),
('CRYPTO', 'ETC',  '이더리움클래식',  10,   5000, TRUE, CURRENT_TIMESTAMP(6));
