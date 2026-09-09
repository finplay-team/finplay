package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepositoryCustom {

	List<Order> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize);

	List<Order> findByAccountIdAndStatusWithCursor(
		Long accountId, OrderStatus status, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize);
}
