package com.finplay.api.domain.education.marketpractice.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record PracticeTutorialChartResponse(
	Long attemptId,
	long runNumber,
	Long instrumentId,
	LocalDateTime virtualDateTime,
	int secondsPerVirtualMinute,
	List<PracticeTutorialCandleResponse> candles,
	String scenarioStage,
	Boolean scenarioProgressing,
	String causeStatus,
	List<PracticeScenarioEventResponse> revealedEvents,
	PriceGuideRangeResponse priceGuideRange) {
	public PracticeTutorialChartResponse {
		candles = List.copyOf(candles);
		revealedEvents = revealedEvents == null ? List.of() : List.copyOf(revealedEvents);
	}
}
