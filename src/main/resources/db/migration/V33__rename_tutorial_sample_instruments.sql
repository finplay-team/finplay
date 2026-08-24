-- 튜토리얼 샘플 종목 이름을 "연습용 종목 X"에서 구별되는 고유 이름으로 정정한다(이슈 #339 후속,
-- 프론트가 이름 옆에 이미 "연습용" 배지를 붙이므로 이름 자체에는 반복하지 않는다). V32는 머지된
-- 마이그레이션이라 수정하지 않고 새 버전으로 UPDATE한다(ADR-0004).

UPDATE instruments SET name = '알파전자' WHERE symbol = 'SANDBOX_STK_1';
UPDATE instruments SET name = '베타바이오' WHERE symbol = 'SANDBOX_STK_2';
UPDATE instruments SET name = '감마에너지' WHERE symbol = 'SANDBOX_STK_3';
UPDATE instruments SET name = '알파코인' WHERE symbol = 'SANDBOX_COIN_1';
UPDATE instruments SET name = '베타코인' WHERE symbol = 'SANDBOX_COIN_2';
UPDATE instruments SET name = '감마코인' WHERE symbol = 'SANDBOX_COIN_3';
