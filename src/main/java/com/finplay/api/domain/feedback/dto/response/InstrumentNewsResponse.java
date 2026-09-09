package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import java.time.LocalDate;
import java.util.List;

public record InstrumentNewsResponse(
	LocalDate originTradeDate,
	NewsSummaryScope summaryScope,
	FeedbackContentStatus summaryStatus,
	String summary,
	List<NewsItem> items) {

	public InstrumentNewsResponse {
		items = List.copyOf(items);
	}

	public static InstrumentNewsResponse notYet(LocalDate originTradeDate) {
		return new InstrumentNewsResponse(
			originTradeDate, null, FeedbackContentStatus.NOT_YET, null, List.of());
	}

	public static InstrumentNewsResponse of(
		LocalDate originTradeDate,
		NewsSummaryScope summaryScope,
		FeedbackContentStatus summaryStatus,
		String summary,
		List<NewsItem> items) {
		return new InstrumentNewsResponse(originTradeDate, summaryScope, summaryStatus, summary, items);
	}
}
