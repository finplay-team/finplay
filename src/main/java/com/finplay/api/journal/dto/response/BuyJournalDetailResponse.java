// 매수 투자일기 단건 조회 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.BuyTradeJournal;
import java.time.LocalDateTime;

public record BuyJournalDetailResponse(
	Long journalId, Long buyTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {

	public static BuyJournalDetailResponse from(BuyTradeJournal journal) {
		return new BuyJournalDetailResponse(
			journal.getId(),
			journal.getBuyTrade().getId(),
			journal.getContent(),
			journal.getCreatedAt(),
			journal.getUpdatedAt());
	}
}
