package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.crypto")
public record FeedbackCryptoProperties(
	@DefaultValue("30")
	int cooldownMinutes,
	@DefaultValue("6")
	int dailyLimit,
	@DefaultValue("5")
	int rollingWindowMinutes,
	@DefaultValue("24")
	int sigmaLookbackHours,
	@DefaultValue("100")
	int minSampleCount,
	@DefaultValue("35")
	int matchBeforeMinutes,
	@DefaultValue("45")
	int watchLockTtlSeconds) {

	public FeedbackCryptoProperties {
		if (rollingWindowMinutes < 1) {
			throw new IllegalArgumentException("feedback.crypto.rolling-window-minutes는 1 이상이어야 합니다.");
		}
		if (sigmaLookbackHours < 1) {
			throw new IllegalArgumentException("feedback.crypto.sigma-lookback-hours는 1 이상이어야 합니다.");
		}
		if (minSampleCount < 1) {
			throw new IllegalArgumentException("feedback.crypto.min-sample-count는 1 이상이어야 합니다.");
		}
		if (matchBeforeMinutes < 0) {
			throw new IllegalArgumentException("feedback.crypto.match-before-minutes는 0 이상이어야 합니다.");
		}
		if (watchLockTtlSeconds < 1) {
			throw new IllegalArgumentException("feedback.crypto.watch-lock-ttl-seconds는 1 이상이어야 합니다.");
		}
	}
}
