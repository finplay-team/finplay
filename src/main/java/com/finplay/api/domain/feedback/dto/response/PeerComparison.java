package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import java.math.BigDecimal;

public record PeerComparison(
	PostSellFeedbackStatus status,
	Long priceMoveId,
	Integer holderCount,
	BigDecimal soldWithin30MinRate,
	Integer medianMinutesToSell,
	Integer yourMinutesToSell) {
}
