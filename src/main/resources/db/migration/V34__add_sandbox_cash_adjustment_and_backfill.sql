-- sandbox_cash_adjustment 컬럼을 추가하고, 과거 샌드박스 매매·튜토리얼 완료 보상 이력으로부터
-- 재계산해 채운다. 또한 이미 오염된 accounts.realized_pnl을 실제 종목 매도 합계로 재계산해 정정한다
-- (033-exclude-tutorial-sandbox-data, 이슈 #366). 컬럼 추가는 파괴적 변경이 아니므로(ADR-0021 §결정7)
-- 2단계 배포로 나누지 않는다. 두 UPDATE는 모두 전체 덮어쓰기(=)이지 누적(+=)이 아니므로 재실행해도
-- 항상 같은 결과를 낸다(멱등). 머지된 마이그레이션은 수정 금지 (ADR-0004).

ALTER TABLE accounts ADD COLUMN sandbox_cash_adjustment BIGINT NOT NULL DEFAULT 0;

-- sandbox_cash_adjustment 재계산: 샌드박스 종목 매매의 현금 순변동 합계 + 이미 지급된 튜토리얼 완료
-- 보상(practice_completions 1행당 5,000,000원, PracticeHoldingReflectionService.createReflection이
-- 매 완료마다 정확히 1번만 호출하고 UNIQUE(user_id, tutorial_key) 제약이 있어 COUNT(*)로 역산 가능).
-- tutorial_key -> market 매핑: INVESTMENT_PRACTICE_V1(STOCK), COIN_PRACTICE_V1(CRYPTO).
UPDATE accounts a
SET a.sandbox_cash_adjustment =
	COALESCE((
		SELECT SUM(CASE WHEN t.side = 'SELL' THEN t.amount - t.fee ELSE -(t.amount + t.fee) END)
		FROM trades t
		JOIN instruments i ON t.instrument_id = i.id
		WHERE t.account_id = a.id
		  AND i.is_tutorial_sample = TRUE
	), 0)
	+ (
		SELECT COUNT(*) * 5000000
		FROM practice_completions pc
		WHERE pc.user_id = a.user_id
		  AND ((pc.tutorial_key = 'INVESTMENT_PRACTICE_V1' AND a.market = 'STOCK')
		    OR (pc.tutorial_key = 'COIN_PRACTICE_V1' AND a.market = 'CRYPTO'))
	);

-- realized_pnl 재계산: 실제 종목(is_tutorial_sample = FALSE) 매도 체결의 realized_pnl 합계만 남긴다
-- (SANDBOX-EXCL-005). 샌드박스 매도 이력이 없는 계좌는 재계산 결과가 기존 값과 같아 변하지 않는다.
UPDATE accounts a
SET a.realized_pnl = COALESCE((
	SELECT SUM(t.realized_pnl)
	FROM trades t
	JOIN instruments i ON t.instrument_id = i.id
	WHERE t.account_id = a.id
	  AND t.side = 'SELL'
	  AND i.is_tutorial_sample = FALSE
), 0);
