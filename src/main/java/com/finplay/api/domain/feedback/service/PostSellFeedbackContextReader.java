package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.SellAllocationQueryService;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PostSellFeedbackContextReader {

	private final TradeService tradeService;

	private final SellAllocationQueryService sellAllocationQueryService;

	@Transactional(readOnly = true)
	PostSellFeedbackContext loadContext(Long userId, Long tradeId) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		if (trade.getSide() != OrderSide.SELL) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		SellAllocationSummaryDto allocation = sellAllocationQueryService.getSellAllocationSummary(tradeId);

		Hibernate.initialize(trade.getInstrument());
		Hibernate.initialize(trade.getStockReplaySession());
		return new PostSellFeedbackContext(trade, allocation);
	}

}
