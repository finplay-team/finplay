// 매도 투자일기 작성 성공 응답 DTO
package com.finplay.api.domain.journal.dto.response;

import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.time.LocalDateTime;

public record SellJournalResponse(Long journalId, Long sellTradeId, String content, LocalDateTime createdAt) {

	public static SellJournalResponse from(SellTradeJournal journal) {
		return new SellJournalResponse(
			journal.getId(), journal.getSellTrade().getId(), journal.getContent(), journal.getCreatedAt());
	}
}
