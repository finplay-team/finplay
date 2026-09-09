package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.time.LocalDateTime;
import java.util.List;

public interface SellTradeJournalRepositoryCustom {

	List<SellTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize);
}
