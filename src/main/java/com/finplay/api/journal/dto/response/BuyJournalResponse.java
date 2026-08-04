// 매수 투자일기 작성 성공 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.BuyTradeJournal;
import java.time.LocalDateTime;

public record BuyJournalResponse(Long journalId, Long buyTradeId, String content, LocalDateTime createdAt) {

	public static BuyJournalResponse from(BuyTradeJournal journal) {
		return new BuyJournalResponse(
			journal.getId(), journal.getBuyTrade().getId(), journal.getContent(), journal.getCreatedAt());
	}
}
