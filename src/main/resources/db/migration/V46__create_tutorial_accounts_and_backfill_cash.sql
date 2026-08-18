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

-- 배포 시점에 이미 존재하는 샌드박스 지정가 매수 PENDING 주문을 취소하고 실제 계좌 예약을 반환한다(PR #452
-- 리뷰 권장 1번). 047 이전 코드는 이런 주문의 현금을 실제 Account에 예약해 뒀는데, 047 이후 체결·취소·수정
-- 경로(LimitOrderFillService·LimitOrderCancelService·LimitOrderModifyService)는 샌드박스 종목이면 항상
-- 튜토리얼 계좌(이 시점 예약 0원)를 대상으로 하므로, 정리하지 않으면 그 주문은 영구히 체결도 취소도 안 되고
-- 실제 계좌 예약도 영구히 묶인다(TutorialLegacyPendingOrderPostMigrationIntegrationTest로 재현 확인). 매도
-- PENDING은 현금이 아니라 holdings.reserved_quantity를 쓰고 이 spec이 건드리지 않는 영역이라 대상이 아니다.
-- 예약액 계산은 LimitOrderFeeCalculator.calculate와 동일한 공식이다: FLOOR(수량×지정가) +
-- FLOOR(FLOOR(수량×지정가)×0.0005). 해당 행이 없으면 두 UPDATE 모두 자연히 0건 적용된다(멱등).
UPDATE accounts a
JOIN (
    SELECT o.account_id AS account_id,
           SUM(FLOOR(o.quantity * o.limit_price)
               + FLOOR(FLOOR(o.quantity * o.limit_price) * 0.0005)) AS total_reserved
    FROM orders o
    JOIN instruments i ON i.id = o.instrument_id
    WHERE o.status = 'PENDING'
      AND o.side = 'BUY'
      AND o.order_type = 'LIMIT'
      AND i.is_tutorial_sample = TRUE
    GROUP BY o.account_id
) legacy ON legacy.account_id = a.id
SET a.reserved_cash = a.reserved_cash - legacy.total_reserved;

UPDATE orders o
JOIN instruments i ON i.id = o.instrument_id
SET o.status = 'CANCELLED'
WHERE o.status = 'PENDING'
  AND o.side = 'BUY'
  AND o.order_type = 'LIMIT'
  AND i.is_tutorial_sample = TRUE;
