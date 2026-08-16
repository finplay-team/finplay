// 현재 attempt/run의 가상 시각과 결정적 29+1 일봉을 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record PracticeTutorialChartResponse(
	Long attemptId,
	long runNumber,
	Long instrumentId,
	LocalDateTime virtualDateTime,
	int secondsPerVirtualMinute,
	List<PracticeTutorialCandleResponse> candles) {
	public PracticeTutorialChartResponse {
		candles = List.copyOf(candles);
	}
}
