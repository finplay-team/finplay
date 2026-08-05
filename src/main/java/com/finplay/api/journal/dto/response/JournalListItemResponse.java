// 투자일기 목록의 항목 1건(매수 또는 매도 회고)을 표현하는 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.domain.SellTradeJournal;
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
