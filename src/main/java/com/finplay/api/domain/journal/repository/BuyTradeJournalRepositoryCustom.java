package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import java.time.LocalDateTime;
import java.util.List;

public interface BuyTradeJournalRepositoryCustom {

	List<BuyTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize);
}
