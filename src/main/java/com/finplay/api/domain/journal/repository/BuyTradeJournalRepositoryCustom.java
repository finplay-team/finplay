// 계좌 단위 매수 투자일기 커서 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import java.time.LocalDateTime;
import java.util.List;

public interface BuyTradeJournalRepositoryCustom {

	List<BuyTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize);
}
