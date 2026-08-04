// 매도 투자일기 작성 성공 응답 DTO
package com.finplay.api.journal.dto.response;

import com.finplay.api.journal.domain.SellTradeJournal;
import java.time.LocalDateTime;

public record SellJournalResponse(Long journalId, Long sellTradeId, String content, LocalDateTime createdAt) {

	public static SellJournalResponse from(SellTradeJournal journal) {
		return new SellJournalResponse(
			journal.getId(), journal.getSellTrade().getId(), journal.getContent(), journal.getCreatedAt());
	}
}
