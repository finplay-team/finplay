-- 오늘 재생 중인 원본 거래일과 준비상태(PREPARING·READY·FAILED)의 정본 테이블을 생성한다.
-- OPEN·CLOSED는 이 테이블에 저장하지 않는다 — Clock과 조합해 조회 시점에 계산한다 (spec.md 비즈니스 규칙).
-- 상태별 nullable 규칙은 애플리케이션 검증(엔티티 정적 팩토리)으로만 강제한다 — DB CHECK 제약 없음 (spec.md 명시).
-- 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE stock_replay_sessions (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    service_date         DATE        NOT NULL,
    source_trading_date  DATE        NULL,
    preparation_status   VARCHAR(20) NOT NULL,
    resolved_at          DATETIME(6) NULL,
    failure_reason       VARCHAR(255) NULL,
    created_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_replay_sessions_service_date UNIQUE (service_date)
);
