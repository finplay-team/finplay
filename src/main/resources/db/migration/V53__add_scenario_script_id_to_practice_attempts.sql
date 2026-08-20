-- 이 실행이 어느 대본으로 가격을 만드는지를 attempt에 영속한다 (049 ORDERBASICS-018, plan §2).
--
-- 단계 진행 판정(#503)에서 파생하지 않는 이유는 대본이 바뀌는 시점 때문이다. 파생하면 사용자가 지정가
-- 매도를 체결하는 그 순간 대본이 갈리는데, 그때 scenario_stage_id에는 직전 대본의 구간 id가 살아 있어
-- "대본에 없는 구간입니다"로 조회·tick·주문이 전부 500이 된다. 대본 식별자는 커서만큼 내구적이어야 하므로
-- 커서 다섯 컬럼과 같은 행에 둔다.
--
-- 추가형 nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다. 백필하지 않는다 — NULL의 해석은
-- PracticeAttempt.scenarioScriptId()가 한 곳에서 한다(생성기 버전 2 + NULL = 049 이전에 시작한 실행 =
-- CRYPTO_STORY_V1). CHECK 제약을 걸지 않는 이유는 값 집합이 열거형이라 대본이 늘 때마다 마이그레이션을
-- 또 쓰게 되기 때문이고(머지된 마이그레이션은 수정 금지, ADR-0004), 인덱스를 두지 않는 이유는 attempt를
-- 항상 (user_id, market) 또는 id로 찾기 때문이다.

ALTER TABLE practice_attempts
    ADD COLUMN scenario_script_id VARCHAR(32) NULL AFTER generator_version;
