package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.journal")
public record FeedbackJournalProperties(
	@DefaultValue("3")
	int maxBuyJournals,
	@DefaultValue("500")
	int maxJournalChars) {

	public FeedbackJournalProperties {
		if (maxBuyJournals < 1) {
			throw new IllegalArgumentException("feedback.journal.max-buy-journals는 1 이상이어야 합니다.");
		}
		if (maxJournalChars < 1) {
			throw new IllegalArgumentException("feedback.journal.max-journal-chars는 1 이상이어야 합니다.");
		}
	}
}
