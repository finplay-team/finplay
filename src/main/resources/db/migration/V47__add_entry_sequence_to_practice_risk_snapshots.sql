-- 튜토리얼 재진입(손절 후 재매수)을 위해 practice_risk_snapshots를 진입 단위로 확장한다.
-- 기존 UNIQUE(attempt_id, run_number)는 한 실행 세대에 snapshot 하나만 허용하므로 재진입이 불가능하다.
--
-- 파괴적 변경(제약 삭제)은 한 배포에 담지 않는다 (ADR-0021 §결정 7). 이 마이그레이션은 추가만 하고,
-- 기존 제약 삭제는 코드 대응이 끝난 뒤 별도 배포에서 한다.
--
-- 새 UNIQUE의 선두 컬럼은 반드시 attempt_id다 — 후속 배포에서 기존 UNIQUE를 삭제할 때 MySQL이
-- FK fk_practice_risk_snapshots_attempt가 사용할 인덱스를 요구하는데, 이 UNIQUE가 그 역할을 이어받는다.
-- 컬럼 순서를 바꾸면 그 삭제가 FK 오류로 막힌다.

ALTER TABLE practice_risk_snapshots
    ADD COLUMN entry_sequence INT NOT NULL DEFAULT 1 AFTER run_number,
    ADD CONSTRAINT uk_practice_risk_snapshots_attempt_run_seq
        UNIQUE (attempt_id, run_number, entry_sequence);
