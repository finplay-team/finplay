-- trade_feedbacks에 투자일기 반영용 컬럼 2개를 추가한다(012-ai-feedback §FEED-013). 머지된 마이그레이션은 수정 금지 (ADR-0004).

ALTER TABLE trade_feedbacks
    -- 서술을 만들 때 프롬프트에 실린 일기의 지문(SHA-256 hex 64자 고정). NULL이면 그때 일기가 없었다는 뜻이고,
    -- NULL → 값도 "달라짐"이라 일기를 나중에 쓴 경로에서 재생성이 열린다.
    ADD COLUMN journal_fingerprint   VARCHAR(64) NULL AFTER regeneration_attempts,
    -- 일기 사유 재생성 누적 횟수. regeneration_attempts와 따로 센다 — 합치면 일기를 여러 번 고친 체결이
    -- 흐름·집단 반영 기회를 잃는다.
    ADD COLUMN journal_regenerations INT         NOT NULL DEFAULT 0 AFTER journal_fingerprint;
