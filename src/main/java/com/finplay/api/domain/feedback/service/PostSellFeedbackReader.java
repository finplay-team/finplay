package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PostSellFeedbackReader {

	private final PostSellFeedbackContextReader postSellFeedbackContextReader;

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader;

	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader;

	public PostSellFeedbackResponse read(Long userId, Long tradeId) {
		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(userId, tradeId);
		Trade trade = context.trade();
		SellAllocationSummaryDto allocation = context.allocation();

		return trade.getInstrument().getMarket() == Market.CRYPTO
			? cryptoPostSellFeedbackReader.read(trade, allocation)
			: stockPostSellFeedbackReader.read(trade, allocation);
	}

}
