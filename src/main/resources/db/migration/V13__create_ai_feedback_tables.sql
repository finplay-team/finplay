-- AI 피드백(012-ai-feedback)의 7개 테이블을 일괄 생성한다. 컬럼 타입·NULL은 spec §C-8, 코인의 시각 컬럼과 유니크 근거는 §C-9다.
-- 일곱 테이블이 FK로 물려 있어 파일을 쪼개지 않는다 — 중간 번호에서 ddl-auto=validate가 깨진다.
-- 기존 원장 테이블(orders·trades·accounts·balances·holdings·손익)에 대한 ALTER·DROP은 한 줄도 없다 (원장 불변).
-- 엔티티 매핑은 이 마이그레이션에 없다 — 테이블만 있고 매핑이 없는 상태는 validate가 문제 삼지 않는다.
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

-- 뉴스·공시 통합. 본문 컬럼을 두지 않는다 (저작권 — 제목·언론사·URL·발행시각만 노출한다).
CREATE TABLE market_news_items (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT       NOT NULL,
    type          VARCHAR(20)  NOT NULL,
    title         VARCHAR(500) NOT NULL,
    publisher     VARCHAR(100) NOT NULL,
    url           VARCHAR(500) NOT NULL,
    published_at  DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- url 단독 유니크로 두면 안 된다. "반도체 업황 둔화" 같은 기사는 삼성전자·SK하이닉스 검색 결과에
    -- 모두 나오는데, 먼저 저장된 한 종목만 남고 나머지는 근거 0건이 되어 카드가 생성되지 않는다.
    -- url(191) 접두로 두어서도 안 된다 — 앞 191자가 같고 쿼리 파라미터만 다른 링크를 중복 판정해
    -- 근거 기사를 조용히 버린다. VARCHAR(500) utf8mb4(2000B) + BIGINT(8B)는 DYNAMIC row format의
    -- 인덱스 키 상한 3072B 안에 들어간다 (§C-8).
    CONSTRAINT uk_market_news_items_instrument_url UNIQUE (instrument_id, url),
    CONSTRAINT fk_market_news_items_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    -- created_at은 발행 시각이 아니라 수집 시각이다. 요약 재생성 판정에 쓰인다 (FEED-008).
    INDEX idx_market_news_items_instrument_published_at (instrument_id, published_at)
);

-- 변동 구간 원장 + 서술. 주식은 window_start/window_end를 채우고 occurred_at이 NULL, 코인은 반대다 (§C-9).
CREATE TABLE price_move_events (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    instrument_id     BIGINT        NOT NULL,
    market            VARCHAR(20)   NOT NULL,
    event_type        VARCHAR(20)   NOT NULL,
    origin_trade_date DATE          NOT NULL,
    window_start      TIME          NULL,
    window_end        TIME          NULL,
    occurred_at       DATETIME(6)   NULL,
    change_rate       DECIMAL(10,6) NOT NULL,
    detection_score   DECIMAL(10,4) NOT NULL,
    narrative         TEXT          NULL,
    narrative_source  VARCHAR(20)   NOT NULL,
    reveal_time       TIME          NULL,
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- event_type이 유니크에 들어가야 한다. 장중 루프의 첫 후보는 t = 09:05이고 windowStart = t - W = 09:00인데,
    -- 시가 갭 카드의 windowStart도 09:00이다. event_type이 없으면 둘이 충돌해 FEED-004의 "존재 시 건너뜀"에
    -- 걸려 나중에 삽입되는 쪽이 조용히 사라진다. 갭이 큰 날은 개장 직후도 급변하는 날이라 하필 고신호 종목에서
    -- 이 충돌이 난다.
    CONSTRAINT uk_price_move_events_instrument_date_type_start UNIQUE (instrument_id, origin_trade_date, event_type, window_start),
    CONSTRAINT fk_price_move_events_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    -- 코인 "최근 24시간" 조회용 (§C-2).
    INDEX idx_price_move_events_instrument_occurred_at (instrument_id, occurred_at)
);

-- 이벤트 ↔ 근거 기사 (N:M).
CREATE TABLE price_move_event_sources (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    price_move_event_id BIGINT NOT NULL,
    market_news_item_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_price_move_event_sources_event_news UNIQUE (price_move_event_id, market_news_item_id),
    CONSTRAINT fk_price_move_event_sources_event FOREIGN KEY (price_move_event_id) REFERENCES price_move_events (id),
    CONSTRAINT fk_price_move_event_sources_news FOREIGN KEY (market_news_item_id) REFERENCES market_news_items (id)
);

-- 종목·거래일 요약 (전 회원 공유). 주식은 PRE_MARKET·FULL 2건, 코인은 ROLLING_24H 1건을 UPSERT 한다.
CREATE TABLE instrument_news_summaries (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    instrument_id     BIGINT      NOT NULL,
    -- 코인도 채운다 — NULL이면 MySQL 유니크가 중복을 허용해 갱신이 아니라 새 행이 쌓인다 (§C-9).
    origin_trade_date DATE        NOT NULL,
    scope             VARCHAR(20) NOT NULL,
    -- 요약에는 템플릿이 없어 narrative_source가 NONE이 될 수 있고, 그때 summary가 NULL이다 (§C-4·§C-8).
    summary           TEXT        NULL,
    narrative_source  VARCHAR(20) NOT NULL,
    generated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instrument_news_summaries_instrument_date_scope UNIQUE (instrument_id, origin_trade_date, scope),
    CONSTRAINT fk_instrument_news_summaries_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    -- 코인 최신 1행 조회용 (FEED-008 — "오늘 날짜 행"이 아니라 generated_at 최신 1건을 본다).
    INDEX idx_instrument_news_summaries_instrument_scope_generated_at (instrument_id, scope, generated_at)
);

-- 시장·거래일 개장 전 브리핑 (전 회원 공유). items는 저장하지 않고 조회 시 §C-2 구간으로 다시 질의한다.
CREATE TABLE market_briefings (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    market            VARCHAR(20) NOT NULL,
    origin_trade_date DATE        NOT NULL,
    summary           TEXT        NULL,
    narrative_source  VARCHAR(20) NOT NULL,
    generated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_market_briefings_market_date UNIQUE (market, origin_trade_date),
    -- 코인 최신 1행 조회용.
    INDEX idx_market_briefings_market_generated_at (market, generated_at)
);

-- 카드별 집단 행동 집계 (장 마감 배치). 회원 식별자를 담지 않는다 — 집계 결과만 저장한다.
CREATE TABLE price_move_peer_stats (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    price_move_event_id     BIGINT      NOT NULL,
    service_date            DATE        NOT NULL,
    holder_count            INT         NOT NULL,
    sold_within_30min_count INT         NOT NULL,
    -- 전원 미매도면 NULL이다 (§C-8).
    median_minutes_to_sell  INT         NULL,
    aggregated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- service_date가 없으면 같은 원본 거래일 재재생 시 첫날 집계가 덮어써진다 (§C-9).
    CONSTRAINT uk_price_move_peer_stats_event_service_date UNIQUE (price_move_event_id, service_date),
    CONSTRAINT fk_price_move_peer_stats_event FOREIGN KEY (price_move_event_id) REFERENCES price_move_events (id)
);

-- 매도 직후 서술 (회원별). trade_id는 order의 trades(id)를 읽기만 한다 — 원장을 쓰는 경로를 만들지 않는다.
CREATE TABLE trade_feedbacks (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    trade_id              BIGINT      NOT NULL,
    narrative             TEXT        NULL,
    narrative_source      VARCHAR(20) NOT NULL,
    -- §C-5의 재생성 게이트를 통과한 서술인지. "장 마감이 지났는지"가 아니다 — 집단 비교 확정까지 반영해야 TRUE다.
    narrative_finalized   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- 체결 1건당 누적 재시도 횟수. 날짜로 리셋하지 않는다 (재생성 실패 시 generated_at을 갱신하지 않아
    -- 날짜 기준이 성립하지 않는다).
    regeneration_attempts INT         NOT NULL DEFAULT 0,
    generated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_trade_feedbacks_trade UNIQUE (trade_id),
    CONSTRAINT fk_trade_feedbacks_trade FOREIGN KEY (trade_id) REFERENCES trades (id)
);
