package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.llm")
public record FeedbackLlmProperties(
	@DefaultValue("gpt-5.4-mini")
	String model,
	@DefaultValue("20")
	int timeoutSeconds,
	@DefaultValue("1024")
	int maxTokens,
	@DefaultValue("1")
	int maxRegeneration,
	@DefaultValue("3")
	int maxNarrativeRetry,
	@DefaultValue("3")
	int maxJournalRegeneration) {

	public FeedbackLlmProperties {
		if (maxRegeneration < 0) {
			throw new IllegalArgumentException("feedback.llm.max-regeneration은 0 이상이어야 합니다.");
		}
	}
}
