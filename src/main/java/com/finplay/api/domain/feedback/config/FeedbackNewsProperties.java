package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feedback.news")
public record FeedbackNewsProperties(
	@DefaultValue("0 0/30 * * * *")
	String collectCron,
	@DefaultValue("0 0/30 8-20 * * MON-FRI")
	String disclosureCron,
	@DefaultValue("30")
	int matchBeforeMinutes,
	@DefaultValue("5")
	int matchAfterMinutes,
	@DefaultValue("5")
	int maxSourcesPerCard,
	@DefaultValue("50")
	int maxItemsPerNewsList,
	@DefaultValue("30")
	int maxItemsPerBriefing,
	@DefaultValue("30")
	int maxItemsPerSummary) {

	public FeedbackNewsProperties {
		if (matchBeforeMinutes < 0 || matchAfterMinutes < 0) {
			throw new IllegalArgumentException("feedback.news의 근거창 폭은 0 이상이어야 합니다.");
		}
		if (maxSourcesPerCard < 1) {
			throw new IllegalArgumentException("feedback.news.max-sources-per-card는 1 이상이어야 합니다.");
		}
		if (maxItemsPerNewsList < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-news-list는 1 이상이어야 합니다.");
		}
		if (maxItemsPerBriefing < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-briefing은 1 이상이어야 합니다.");
		}
		if (maxItemsPerSummary < 1) {
			throw new IllegalArgumentException("feedback.news.max-items-per-summary는 1 이상이어야 합니다.");
		}
	}
}
