// 계좌 단위 주문 내역 커서 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepositoryCustom {

	List<Order> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize);

	List<Order> findByAccountIdAndStatusWithCursor(
		Long accountId, OrderStatus status, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize);
}
