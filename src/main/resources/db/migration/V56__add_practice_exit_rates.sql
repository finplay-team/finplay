-- 튜토리얼 손절·익절 기준을 프리셋 3개(택1)에서 자유 입력 비율로 넓히는 컬럼을 추가한다 (052).
--
-- **추가만 한다.** exit_preset은 남긴다 — 프론트가 별도 레포·별도 배포라 같은 배포에서 없애면 깨지고,
-- 자동 배포의 롤백은 앱만 되돌리고 스키마는 되돌리지 않으므로 구버전 앱이 그 컬럼을 계속 읽어야 한다
-- (CLAUDE.md 규칙 8, ADR-0021 §결정 7). 삭제는 프론트 전환이 끝난 뒤 별도 배포에서 한다.
--
-- 052 이후 exit_preset의 의미는 "고른 프리셋"이 아니라 **"적용된 비율이 프리셋 3개 중 하나와 정확히
-- 같은가"의 표시**다. 자유 조합(예: 손절 5 + 익절 3)이면 NULL이 들어간다. V51의 CHECK는 그대로 성립한다.
--
-- 단위·정밀도는 exit_plans.stop_loss_rate와 같은 DECIMAL(7,4)이며 **퍼센트 수**다(3%는 0.03이 아니라 3).
-- 손절률도 **양수**로 저장하고 부호는 ReferencePriceCalculator가 붙인다.
--
-- 두 컬럼이 모두 NULL이면 "미선택"이며 애플리케이션이 exit_preset -> 기본값(손절 3·익절 5) 순으로
-- 해석한다(042 EXITPRESET-002 승계 — 아무것도 고르지 않은 사용자의 결과가 이 배포로 달라지면 안 된다).

ALTER TABLE practice_attempts
    ADD COLUMN exit_stop_loss_rate   DECIMAL(7, 4) NULL AFTER exit_preset,
    ADD COLUMN exit_take_profit_rate DECIMAL(7, 4) NULL AFTER exit_stop_loss_rate;

-- 한쪽만 채워진 상태는 없다. 애플리케이션(PracticeAttempt.selectExitRates)이 항상 둘을 함께 쓰지만,
-- V51이 exit_plans의 귀속 두 컬럼에 같은 모양의 CHECK를 둔 것과 같은 이유로 스키마에서도 막는다.
-- **범위(2~5 / 3~8)는 CHECK로 박지 않는다** — 그 구간은 제품이 조정하는 값이고 서버가 exitRateBounds로
-- 클라이언트에 내려보내는 파라미터라, 스키마에 박으면 조정할 때마다 마이그레이션이 필요해진다.
ALTER TABLE practice_attempts
    ADD CONSTRAINT chk_practice_attempts_exit_rates CHECK (
        (exit_stop_loss_rate IS NULL AND exit_take_profit_rate IS NULL)
        OR (exit_stop_loss_rate IS NOT NULL AND exit_take_profit_rate IS NOT NULL
            AND exit_stop_loss_rate > 0 AND exit_take_profit_rate > 0)
    );

ALTER TABLE practice_risk_snapshots
    ADD COLUMN exit_stop_loss_rate   DECIMAL(7, 4) NULL AFTER exit_preset,
    ADD COLUMN exit_take_profit_rate DECIMAL(7, 4) NULL AFTER exit_stop_loss_rate;

ALTER TABLE practice_risk_snapshots
    ADD CONSTRAINT chk_practice_risk_snapshots_exit_rates CHECK (
        (exit_stop_loss_rate IS NULL AND exit_take_profit_rate IS NULL)
        OR (exit_stop_loss_rate IS NOT NULL AND exit_take_profit_rate IS NOT NULL
            AND exit_stop_loss_rate > 0 AND exit_take_profit_rate > 0)
    );

-- 백필. **NULL 해석과 결과가 어긋나지 않는다** — 애플리케이션의 폴백이 exit_preset을 바로 이 값으로
-- 읽으므로 백필한 행과 백필하지 않은 행의 동작이 같다. 그런데도 채우는 이유는 practice_risk_snapshots가
-- "그때 실제로 무엇이 적용됐는가"를 보존하는 불변 기록이고, 프리셋이 언젠가 삭제되면 그 행들이 근거를
-- 잃기 때문이다. 여기서 지어내는 값은 없다 — V51의 CHECK가 허용한 세 값을 ExitPreset의 정의대로 옮길 뿐이다.
--
-- 폴백은 그래도 지운다는 뜻이 아니다. 이 배포와 롤백 사이에 구버전 앱이 exit_preset만 쓴 행을 새로 만들 수
-- 있어 폴백이 계속 필요하다(PracticeAttempt.effectiveExitRates 주석).
UPDATE practice_attempts
SET exit_stop_loss_rate   = CASE exit_preset
                                WHEN 'CAUTIOUS' THEN 2
                                WHEN 'BALANCED' THEN 3
                                WHEN 'RELAXED' THEN 5
                            END,
    exit_take_profit_rate = CASE exit_preset
                                WHEN 'CAUTIOUS' THEN 3
                                WHEN 'BALANCED' THEN 5
                                WHEN 'RELAXED' THEN 8
                            END
WHERE exit_preset IS NOT NULL;

UPDATE practice_risk_snapshots
SET exit_stop_loss_rate   = CASE exit_preset
                                WHEN 'CAUTIOUS' THEN 2
                                WHEN 'BALANCED' THEN 3
                                WHEN 'RELAXED' THEN 5
                            END,
    exit_take_profit_rate = CASE exit_preset
                                WHEN 'CAUTIOUS' THEN 3
                                WHEN 'BALANCED' THEN 5
                                WHEN 'RELAXED' THEN 8
                            END
WHERE exit_preset IS NOT NULL;
