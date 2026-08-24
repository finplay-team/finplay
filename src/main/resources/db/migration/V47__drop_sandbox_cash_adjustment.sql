-- accounts.sandbox_cash_adjustment 컬럼을 물리적으로 삭제한다(#459 PR-B, 047 "제외 범위" 후속).
-- V34(033)가 추가한 컬럼이고, V46(047)이 그 값을 cash_balance로 백필해 0으로 정리했으며, #459 PR-A(#460)가
-- 이 컬럼을 읽고 쓰던 Account.sandboxCashAdjustment 엔티티 매핑을 이미 제거해 배포까지 확인한 상태다
-- (ADR-0021 §결정 7의 파괴적 스키마 변경 2단계 배포 — ①매핑 제거 배포 확인 후 ②이 DROP). 머지된
-- 마이그레이션은 수정 금지(ADR-0004)이므로 V34·V46은 그대로 두고 이 파일로만 제거한다.

ALTER TABLE accounts DROP COLUMN sandbox_cash_adjustment;
