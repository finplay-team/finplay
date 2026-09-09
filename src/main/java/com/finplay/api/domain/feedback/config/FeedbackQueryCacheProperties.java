package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.query-cache")
public record FeedbackQueryCacheProperties(
	@DefaultValue("true")
	boolean enabled,
	@DefaultValue("1000")
	long lockTtlMillis,
	@DefaultValue("300")
	long waitMillis,
	@DefaultValue("20")
	long pollMillis) {

	public FeedbackQueryCacheProperties {
		if (lockTtlMillis < 1) {
			throw new IllegalArgumentException("feedback.query-cache.lock-ttl-millis는 1 이상이어야 합니다.");
		}
		if (waitMillis < 0) {
			throw new IllegalArgumentException("feedback.query-cache.wait-millis는 0 이상이어야 합니다.");
		}
		if (pollMillis < 1) {
			throw new IllegalArgumentException("feedback.query-cache.poll-millis는 1 이상이어야 합니다.");
		}
	}
}
