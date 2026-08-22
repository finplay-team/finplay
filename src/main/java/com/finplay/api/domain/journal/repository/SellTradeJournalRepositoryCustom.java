// 계좌 단위 매도 회고 커서 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.time.LocalDateTime;
import java.util.List;

public interface SellTradeJournalRepositoryCustom {

	List<SellTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize);
}
