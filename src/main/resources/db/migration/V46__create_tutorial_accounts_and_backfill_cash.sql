-- 튜토리얼 전용 계좌 테이블을 신설하고, sandbox_cash_adjustment로 이미 부풀려진 실제 계좌 현금을
-- 원복한다(047-tutorial-sandbox-cash-isolation, 이슈 #450). 머지된 마이그레이션은 수정 금지 (ADR-0004).

CREATE TABLE tutorial_accounts (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    market       VARCHAR(20) NOT NULL,
    cash_balance BIGINT      NOT NULL,
    reserved_cash BIGINT     NOT NULL,
    realized_pnl BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tutorial_accounts_user_market UNIQUE (user_id, market),
    CONSTRAINT fk_tutorial_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 배포 시점까지 sandbox_cash_adjustment(033)로만 보정되던 실제 계좌 현금을 원복한다(TUTORIAL-CASH-ISOL-008).
-- sandbox_cash_adjustment는 튜토리얼 매매 순현금뿐 아니라 완료 보상(500만원)도 함께 누적해 왔다
-- (PracticeHoldingReflectionService.payTutorialCompletionReward가 종목 조건 없이 항상
-- addSandboxCashAdjustment(5,000,000)을 호출했다). 완료 보상은 TUTORIAL-CASH-ISOL-004에 따라 실제
-- 계좌에 남는 게 의도한 동작이라 원복 대상이 아니다 — 그대로 다 빼면 이미 보상을 실제 매매에 쓴
-- 사용자의 cash_balance가 음수가 될 수 있다(PR #452 리뷰 차단 2번). practice_completions에 그 시장의
-- tutorial_key 완료 기록이 있는 사용자만 5,000,000을 조정값에서 제외하고 나머지(순수 매매분)만
-- 원복하며, 혹시 모를 나머지 계산 오차에 대비해 0 미만으로는 내려가지 않도록 GREATEST로 한 번 더 막는다.
-- sandbox_cash_adjustment = 0인 행은 WHERE 절에서 자연히 제외되므로 재실행해도 추가 변화가 없다(멱등).
UPDATE accounts a
LEFT JOIN practice_completions pc
    ON pc.user_id = a.user_id
   AND pc.tutorial_key = CASE a.market
                              WHEN 'STOCK' THEN 'INVESTMENT_PRACTICE_V1'
                              WHEN 'CRYPTO' THEN 'COIN_PRACTICE_V1'
                          END
SET a.cash_balance = GREATEST(
        0,
        a.cash_balance - (a.sandbox_cash_adjustment - IF(pc.id IS NULL, 0, 5000000))
    ),
    a.sandbox_cash_adjustment = 0
WHERE a.sandbox_cash_adjustment <> 0;
