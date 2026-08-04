// 매수 투자일기 수정 성공 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.BuyTradeJournal;
import java.time.LocalDateTime;

public record BuyJournalUpdateResponse(
	Long journalId, Long buyTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {

	public static BuyJournalUpdateResponse from(BuyTradeJournal journal) {
		return new BuyJournalUpdateResponse(
			journal.getId(),
			journal.getBuyTrade().getId(),
			journal.getContent(),
			journal.getCreatedAt(),
			journal.getUpdatedAt());
	}
}
