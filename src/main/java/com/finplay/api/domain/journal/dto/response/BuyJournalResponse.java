package com.finplay.api.domain.journal.dto.response;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import java.time.LocalDateTime;

public record BuyJournalResponse(Long journalId, Long buyTradeId, String content, LocalDateTime createdAt) {

	public static BuyJournalResponse from(BuyTradeJournal journal) {
		return new BuyJournalResponse(
			journal.getId(), journal.getBuyTrade().getId(), journal.getContent(), journal.getCreatedAt());
	}
}
