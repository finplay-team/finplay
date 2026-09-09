package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;

public record Counterfactuals(
	PostSellFeedbackStatus status,
	CounterfactualScenario atClose,
	CounterfactualScenario atHoldHigh,
	CounterfactualScenario atFirstMoveAfterBuy) {
}
