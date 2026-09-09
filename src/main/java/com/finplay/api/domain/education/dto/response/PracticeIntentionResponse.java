package com.finplay.api.domain.education.dto.response;

import com.finplay.api.domain.education.model.PracticeIntention;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeIntentionResponse(
	Long intentionId,
	Long instrumentId,
	BigDecimal quantity,
	BigDecimal stopLoss,
	BigDecimal takeProfit,
	LocalDateTime createdAt) {

	public static PracticeIntentionResponse from(PracticeIntention intention) {
		return new PracticeIntentionResponse(
			intention.intentionId(),
			intention.instrumentId(),
			intention.quantity(),
			intention.stopLoss(),
			intention.takeProfit(),
			intention.createdAt());
	}
}
