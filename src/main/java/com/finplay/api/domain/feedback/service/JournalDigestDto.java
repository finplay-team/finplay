package com.finplay.api.domain.feedback.service;

import java.time.LocalDateTime;
import java.util.List;

record JournalDigestDto(String sellJournalContent, List<BuyJournalLine> buyJournals, String fingerprint) {

	JournalDigestDto {
		buyJournals = List.copyOf(buyJournals);
	}

	static JournalDigestDto empty() {
		return new JournalDigestDto(null, List.of(), null);
	}

	boolean isEmpty() {
		return sellJournalContent == null && buyJournals.isEmpty();
	}

	record BuyJournalLine(Long buyTradeId, LocalDateTime buyAt, String content) {
	}
}
