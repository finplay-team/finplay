package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PostSellFlow(
	PostSellFeedbackStatus status,
	BigDecimal closePrice,
	LocalDateTime closeAt,
	BigDecimal sellToCloseRate,
	BigDecimal postSellHighPrice,
	LocalDateTime postSellHighAt) {
}
