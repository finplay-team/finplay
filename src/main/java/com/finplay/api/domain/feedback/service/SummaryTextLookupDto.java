package com.finplay.api.domain.feedback.service;

import java.util.Optional;

public record SummaryTextLookupDto(boolean rowExists, String text) {

	public static SummaryTextLookupDto missingRow() {
		return new SummaryTextLookupDto(false, null);
	}

	public static SummaryTextLookupDto row(String text) {
		return new SummaryTextLookupDto(true, text);
	}

	public Optional<String> readyText() {
		return Optional.ofNullable(text);
	}
}
