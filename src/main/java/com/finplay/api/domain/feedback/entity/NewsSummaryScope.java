package com.finplay.api.domain.feedback.entity;

public enum NewsSummaryScope {

	PRE_MARKET("직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전"),
	FULL("직전 거래일 15:30 ~ 원본 거래일 15:30"),
	ROLLING_24H("최근 24시간");

	private final String promptText;

	NewsSummaryScope(String promptText) {
		this.promptText = promptText;
	}

	public String promptText() {
		return this.promptText;
	}
}
