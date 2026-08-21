-- 진입(risk snapshot)이 열릴 때 attempt가 쓰던 대본 식별자를 진입별로 고정한다 (049 ORDERBASICS-023,
-- spec.md §비즈니스 규칙 "완료 대조 배열의 대본 식별자", plan.md §3-A).
--
-- attempt.scenario_script_id(V53)는 전환(advance-script)이 바뀌는 "지금" 값이라, 완료 대조 배열이 그
-- 값을 그대로 쓰면 2단계에서 열린 진입도 전환 후 "3단계"로 잘못 표시된다. 진입이 열리는 순간(스냅샷 생성
-- 시점)의 값을 스냅샷에 복사해야 진입별로 정확한 대본이 남는다.
--
-- 추가형 nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다. 백필하지 않는다 — NULL의 해석은
-- PracticeEntryComparisonService.toEntry가 호출자가 이미 든 attempt.usesScenarioScript()로 한다(엔티티가
-- 아니라 서비스 계층인 이유는 PracticeRiskSnapshot.attempt가 지연 로딩이라 엔티티 안에서 해석하면 조회마다
-- 로딩이 하나씩 붙기 때문이다). CHECK 제약을 걸지 않는 이유는 값 집합이 열거형이라 대본이 늘 때마다
-- 마이그레이션을 또 쓰게 되기 때문이고(머지된 마이그레이션은 수정 금지, ADR-0004), 인덱스를 두지 않는
-- 이유는 snapshot을 항상 (attempt_id, run_number)로 찾기 때문이다.

ALTER TABLE practice_risk_snapshots
    ADD COLUMN scenario_script_id VARCHAR(32) NULL AFTER exit_preset;
