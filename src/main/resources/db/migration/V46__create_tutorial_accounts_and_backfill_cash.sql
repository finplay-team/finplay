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
-- sandbox_cash_adjustment = 0인 행은 WHERE 절에서 자연히 제외되므로 재실행해도 추가 변화가 없다(멱등).
UPDATE accounts
SET cash_balance = cash_balance - sandbox_cash_adjustment,
    sandbox_cash_adjustment = 0
WHERE sandbox_cash_adjustment <> 0;
