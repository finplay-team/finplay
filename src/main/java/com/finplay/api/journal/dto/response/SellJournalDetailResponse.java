// 매도 투자일기 단건 조회 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.SellTradeJournal;
import java.time.LocalDateTime;

public record SellJournalDetailResponse(
	Long journalId, Long sellTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {

	public static SellJournalDetailResponse from(SellTradeJournal journal) {
		return new SellJournalDetailResponse(
			journal.getId(),
			journal.getSellTrade().getId(),
			journal.getContent(),
			journal.getCreatedAt(),
			journal.getUpdatedAt());
	}
}
