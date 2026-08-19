-- 대본 진행 계산의 delta 기준 시각을 attempt에 영속한다 (041 4번, plan §tick 알고리즘).
--
-- plan §tick 알고리즘은 remaining = min(now - progress_updated_at, MAX_TICK_GAP)을 쓰는데 §데이터 모델의
-- 컬럼 표에는 이 값이 없어 V50이 다섯 개만 넣었다. 기존 updated_at을 재사용하지 않는 이유는 그것이 대본
-- 진행과 무관한 경로(재시작·완료·프리셋 선택·완료 replay 조정)에서도 갱신되기 때문이다 — 그 갱신 하나가
-- 사용자가 실제로 기다린 시간을 0으로 만들어 대본이 그만큼 뒤로 밀린다.
--
-- 추가형 nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다. NULL은 "아직 첫 tick이 오지 않았다"는
-- 뜻이며, 대본 위치 다섯 컬럼과 함께 종목 선택·재시작에서 지워진다.

ALTER TABLE practice_attempts
    ADD COLUMN scenario_progress_updated_at DATETIME(6) NULL AFTER scenario_candle_low;
