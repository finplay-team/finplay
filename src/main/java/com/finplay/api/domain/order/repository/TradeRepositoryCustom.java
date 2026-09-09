package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.List;

public interface TradeRepositoryCustom {

	List<Trade> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorExecutedAt, Long cursorId, int fetchSize);
}
