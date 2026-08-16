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

		// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001과 동일 원칙, 이슈: 포트폴리오 주문 내역 누출) —
		// 이 조회는 GET /api/orders(사용자 노출) 전용이다. 튜토리얼 샌드박스 종목 주문은 실거래 화면에
		// 섞이면 안 된다.
		BooleanBuilder condition = new BooleanBuilder(order.account.id.eq(accountId))
			.and(order.instrument.tutorialSample.eq(false));
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

		// GET /api/orders/pending(사용자 노출) 전용 — 같은 이유로 샌드박스 종목 주문을 제외한다.
		BooleanBuilder condition = new BooleanBuilder(order.account.id.eq(accountId))
			.and(order.status.eq(status))
			.and(order.instrument.tutorialSample.eq(false));
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
