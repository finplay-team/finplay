package com.finplay.api.domain.journal.dto.response;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.time.LocalDateTime;

public record JournalListItemResponse(
	String journalType, Long buyTradeId, Long sellTradeId, String content, LocalDateTime createdAt,
	LocalDateTime updatedAt) {

	public static JournalListItemResponse from(BuyTradeJournal journal) {
		return new JournalListItemResponse(
			"BUY", journal.getBuyTrade().getId(), null, journal.getContent(), journal.getCreatedAt(),
			journal.getUpdatedAt());
	}

	public static JournalListItemResponse from(SellTradeJournal journal) {
		return new JournalListItemResponse(
			"SELL", null, journal.getSellTrade().getId(), journal.getContent(), journal.getCreatedAt(),
			journal.getUpdatedAt());
	}
}
