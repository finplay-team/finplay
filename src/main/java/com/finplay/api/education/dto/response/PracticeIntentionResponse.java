// 저장된 투자 실습 사전 의도의 식별자와 수량·가격을 반환하는 응답 DTO
package com.finplay.api.education.dto.response;

import com.finplay.api.education.domain.PracticeIntention;
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
			intention.getId(),
			intention.getInstrument().getId(),
			intention.getQuantity(),
			intention.getStopLoss(),
			intention.getTakeProfit(),
			intention.getCreatedAt());
	}
}
