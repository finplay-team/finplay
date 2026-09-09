package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;
import java.util.List;

public record MarketBriefingResponse(
	Market market,
	LocalDate originTradeDate,
	FeedbackContentStatus status,
	String summary,
	List<BriefingNewsItem> items) {

	public MarketBriefingResponse {
		items = List.copyOf(items);
	}

	public static MarketBriefingResponse withoutItems(
		Market market, LocalDate originTradeDate, FeedbackContentStatus status) {
		return new MarketBriefingResponse(market, originTradeDate, status, null, List.of());
	}

	public static MarketBriefingResponse of(
		Market market,
		LocalDate originTradeDate,
		FeedbackContentStatus status,
		String summary,
		List<BriefingNewsItem> items) {
		return new MarketBriefingResponse(market, originTradeDate, status, summary, items);
	}
}
