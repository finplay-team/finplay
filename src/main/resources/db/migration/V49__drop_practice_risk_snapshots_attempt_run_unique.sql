-- 한 실행 세대에 진입(매수 체결)이 여럿 생길 수 있도록 기존 UNIQUE(attempt_id, run_number)를 삭제한다.
-- 튜토리얼 재설계의 손절 후 재매수가 이 제약에 막혀 있었다.
--
-- 파괴적 변경이라 추가와 같은 배포에 담지 않는다 (ADR-0021 §결정 7). 앞선 배포에서
-- entry_sequence 컬럼과 uk_practice_risk_snapshots_attempt_run_seq를 먼저 추가했고,
-- 단건 조회 6곳을 진입 단위로 나누는 코드 대응도 이 배포보다 먼저 끝냈다.
--
-- 삭제해도 중복 snapshot은 생기지 않는다 — 새 UNIQUE (attempt_id, run_number, entry_sequence)가
-- 같은 보호를 하고, 재진입 도입 전까지 코드는 항상 entry_sequence = 1을 쓴다.
--
-- 이 삭제가 통과하는 것은 새 UNIQUE의 선두 컬럼이 attempt_id이기 때문이다. MySQL은 FK
-- fk_practice_risk_snapshots_attempt가 쓸 인덱스를 요구하는데 새 UNIQUE가 그 역할을 이어받는다.

ALTER TABLE practice_risk_snapshots
    DROP INDEX uk_practice_risk_snapshots_attempt_run;
