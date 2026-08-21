// 계좌 단위 체결 내역 커서 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.List;

public interface TradeRepositoryCustom {

	List<Trade> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorExecutedAt, Long cursorId, int fetchSize);
}
