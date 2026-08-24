-- 같은 lot이 같은 매도에 두 번 배분되는 중복 행을 DB가 막지 못하던 gap을 유니크 제약으로 메운다 (#534).

ALTER TABLE trade_allocations
    ADD CONSTRAINT uk_trade_allocations_sell_trade_holding_lot UNIQUE (sell_trade_id, holding_lot_id);
