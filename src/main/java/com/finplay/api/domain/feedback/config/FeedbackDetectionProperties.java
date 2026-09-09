package com.finplay.api.domain.feedback.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.detection")
public record FeedbackDetectionProperties(
	@DefaultValue("2.5")
	double zScoreK,
	@DefaultValue("5")
	int windowMinutes,
	@DefaultValue("5")
	int mergeWindowMinutes,
	@DefaultValue("2")
	int maxIntradayCards,
	@DefaultValue("0.01")
	BigDecimal openingGapThreshold) {

	public FeedbackDetectionProperties {
		if (windowMinutes < 1) {
			throw new IllegalArgumentException("feedback.detection.window-minutes는 1 이상이어야 합니다.");
		}
		if (maxIntradayCards < 1) {
			throw new IllegalArgumentException("feedback.detection.max-intraday-cards는 1 이상이어야 합니다.");
		}
		if (openingGapThreshold == null || openingGapThreshold.signum() <= 0) {
			throw new IllegalArgumentException("feedback.detection.opening-gap-threshold는 0보다 커야 합니다.");
		}
	}
}
