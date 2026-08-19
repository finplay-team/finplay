-- 튜토리얼 손절·익절 프리셋 선택값과 OCO 예약의 실행 세대 귀속 컬럼을 추가한다 (042 EXITPRESET-001·004·015).
--
-- 전부 추가형이고 nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다. 이 배포 시점의 앱은 세 컬럼을
-- 읽지도 쓰지도 않는다 — 값을 채우는 것은 선택 API(042 3번)·매수 체결 반영(4번)·자동 예약 생성(5번)이며,
-- 롤백해도 구버전 앱이 컬럼을 모르는 채 그대로 동작한다.
--
-- exit_preset이 NULL인 행은 "미선택"이며 애플리케이션이 기본 프리셋(BALANCED)으로 해석한다
-- (EXITPRESET-002). 기존 snapshot 전부가 이 경우이므로 백필하지 않는다 — 백필은 "그때 실제로 고른 값"을
-- 사후에 지어내는 것이 되고, 해석 규칙 하나로 같은 결과를 얻는다.

ALTER TABLE practice_attempts
    ADD COLUMN exit_preset VARCHAR(20) NULL AFTER scenario_candle_low;

ALTER TABLE practice_attempts
    -- market·status와 같은 방식으로 값 집합을 스키마에서도 막는다(V38). 프리셋을 늘리려면 이 CHECK를 함께
    -- 고쳐야 하며, 그것은 프리셋 수치가 041 대본과 맞물려 있어 어차피 단독으로 바꿀 수 없는 값이다.
    ADD CONSTRAINT chk_practice_attempts_exit_preset CHECK (
        exit_preset IS NULL OR exit_preset IN ('CAUTIOUS', 'BALANCED', 'RELAXED')
    );

ALTER TABLE practice_risk_snapshots
    ADD COLUMN exit_preset VARCHAR(20) NULL AFTER take_profit_price;

ALTER TABLE practice_risk_snapshots
    ADD CONSTRAINT chk_practice_risk_snapshots_exit_preset CHECK (
        exit_preset IS NULL OR exit_preset IN ('CAUTIOUS', 'BALANCED', 'RELAXED')
    );

-- 예약의 튜토리얼 귀속. orders가 V38에서 가진 것과 같은 모양·같은 CHECK다 — tick 정산 대상 선별
-- (EXITPRESET-014)과 재시작 정리(EXITPRESET-015)가 이 두 컬럼으로 이뤄진다.
ALTER TABLE exit_plans
    ADD COLUMN practice_attempt_id BIGINT NULL AFTER replay_session_id,
    ADD COLUMN practice_attempt_run_number BIGINT NULL AFTER practice_attempt_id;

-- 정산·정리가 "현재 실행 세대의 PENDING 예약"만 훑는다(042 6번의 findPendingPracticeRunExitPlanIds).
-- orders의 idx_orders_practice_attempt_run_status와 같은 컬럼 순서다. FK보다 **먼저** 만드는 이유는
-- MySQL이 FK가 쓸 인덱스를 요구하는데, 이 인덱스의 선두 컬럼이 practice_attempt_id라 그 역할을 겸하기
-- 때문이다 — 순서를 뒤집으면 FK 생성 시 단일 컬럼 인덱스가 하나 더 만들어져 남는다.
ALTER TABLE exit_plans
    ADD INDEX idx_exit_plans_practice_attempt_run_status
        (practice_attempt_id, practice_attempt_run_number, status, id);

ALTER TABLE exit_plans
    ADD CONSTRAINT fk_exit_plans_practice_attempt
        FOREIGN KEY (practice_attempt_id) REFERENCES practice_attempts (id),
    ADD CONSTRAINT chk_exit_plans_practice_attempt_attribution CHECK (
        (practice_attempt_id IS NULL AND practice_attempt_run_number IS NULL)
        OR (practice_attempt_id IS NOT NULL AND practice_attempt_run_number IS NOT NULL
            AND practice_attempt_run_number > 0)
    );
