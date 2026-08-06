// 계좌 단위 주문 내역을 커서 페이지네이션으로 조회하는 QueryDSL 구현체
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.domain.QOrder;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

public class OrderRepositoryImpl implements OrderRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public OrderRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<Order> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize) {
		QOrder order = QOrder.order;

		BooleanBuilder condition = new BooleanBuilder(order.account.id.eq(accountId));
		if (cursorRequestedAt != null && cursorId != null) {
			condition.and(
				order.requestedAt.lt(cursorRequestedAt)
					.or(order.requestedAt.eq(cursorRequestedAt).and(order.id.lt(cursorId))));
		}

		return queryFactory
			.selectFrom(order)
			.join(order.instrument).fetchJoin()
			.where(condition)
			.orderBy(order.requestedAt.desc(), order.id.desc())
			.limit(fetchSize)
			.fetch();
	}

	@Override
	public List<Order> findByAccountIdAndStatusWithCursor(
		Long accountId, OrderStatus status, LocalDateTime cursorRequestedAt, Long cursorId, int fetchSize) {
		QOrder order = QOrder.order;

		BooleanBuilder condition = new BooleanBuilder(order.account.id.eq(accountId))
			.and(order.status.eq(status));
		if (cursorRequestedAt != null && cursorId != null) {
			condition.and(
				order.requestedAt.lt(cursorRequestedAt)
					.or(order.requestedAt.eq(cursorRequestedAt).and(order.id.lt(cursorId))));
		}

		return queryFactory
			.selectFrom(order)
			.join(order.instrument).fetchJoin()
			.where(condition)
			.orderBy(order.requestedAt.desc(), order.id.desc())
			.limit(fetchSize)
			.fetch();
	}
}
