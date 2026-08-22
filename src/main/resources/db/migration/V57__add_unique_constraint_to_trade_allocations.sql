ALTER TABLE trade_allocations
    ADD CONSTRAINT uk_trade_allocations_sell_trade_holding_lot UNIQUE (sell_trade_id, holding_lot_id);
