-- 튜토리얼 대본 위치와 진행 중 봉을 attempt에 영속한다 (041 SCENARIO-002·007).
-- 대본 위치는 벽시계에서 파생할 수 없다 — 대기 구간은 되감기고 재진입은 점프하므로 단조가 아니다.
--
-- 전부 추가형이고 nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다. 기존
-- chk_practice_attempts_state_fields는 열거된 컬럼만 검사하므로 충돌하지 않으며, 생성기 버전 1로 만들어진
-- attempt는 이 컬럼들이 계속 NULL이다.
--
-- 분이 아니라 초로 저장하는 이유는 나머지 유실 때문이다. 클라이언트가 3초의 배수가 아닌 간격으로 tick하면
-- (2초 간격이면 매번 2/3 = 0) 나머지가 매 tick 버려져 대본이 영영 진행하지 않는다.

ALTER TABLE practice_attempts
    ADD COLUMN scenario_stage_id              VARCHAR(32)   NULL AFTER generator_version,
    ADD COLUMN scenario_stage_elapsed_seconds BIGINT        NULL AFTER scenario_stage_id,
    ADD COLUMN scenario_candle_open           DECIMAL(18,8) NULL AFTER scenario_stage_elapsed_seconds,
    ADD COLUMN scenario_candle_high           DECIMAL(18,8) NULL AFTER scenario_candle_open,
    ADD COLUMN scenario_candle_low            DECIMAL(18,8) NULL AFTER scenario_candle_high;

ALTER TABLE practice_attempts
    ADD CONSTRAINT chk_practice_attempts_scenario_elapsed CHECK (
        scenario_stage_elapsed_seconds IS NULL OR scenario_stage_elapsed_seconds >= 0
    ),
    -- 진행 중 봉의 세 값은 함께 채워지고 함께 비워진다. 시가는 종목 선택 시점의 첫 가격으로 한 번 정하고
    -- 이후 바꾸지 않으므로 항상 그날의 고가·저가 사이에 있다.
    ADD CONSTRAINT chk_practice_attempts_scenario_candle CHECK (
        (scenario_candle_open IS NULL AND scenario_candle_high IS NULL AND scenario_candle_low IS NULL)
        OR (scenario_candle_open IS NOT NULL AND scenario_candle_high IS NOT NULL
            AND scenario_candle_low IS NOT NULL AND scenario_candle_low > 0
            AND scenario_candle_low <= scenario_candle_open
            AND scenario_candle_open <= scenario_candle_high)
    );
