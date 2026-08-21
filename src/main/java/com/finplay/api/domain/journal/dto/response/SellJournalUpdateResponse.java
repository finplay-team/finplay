// 매도 투자일기 수정 성공 응답 DTO
package com.finplay.api.domain.journal.dto.response;

import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.time.LocalDateTime;

public record SellJournalUpdateResponse(
	Long journalId, Long sellTradeId, String content, LocalDateTime createdAt, LocalDateTime updatedAt) {

	public static SellJournalUpdateResponse from(SellTradeJournal journal) {
		return new SellJournalUpdateResponse(
			journal.getId(),
			journal.getSellTrade().getId(),
			journal.getContent(),
			journal.getCreatedAt(),
			journal.getUpdatedAt());
	}
}
