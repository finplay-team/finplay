// 계좌 단위 체결 내역을 커서 페이지네이션으로 조회하는 QueryDSL 구현체
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.QTrade;
import com.finplay.api.order.domain.Trade;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

public class TradeRepositoryImpl implements TradeRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public TradeRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<Trade> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorExecutedAt, Long cursorId, int fetchSize) {
		QTrade trade = QTrade.trade;

		BooleanBuilder condition = new BooleanBuilder(trade.account.id.eq(accountId));
		if (cursorExecutedAt != null && cursorId != null) {
			condition.and(
				trade.executedAt.lt(cursorExecutedAt)
					.or(trade.executedAt.eq(cursorExecutedAt).and(trade.id.lt(cursorId))));
		}

		return queryFactory
			.selectFrom(trade)
			.join(trade.instrument).fetchJoin()
			.where(condition)
			.orderBy(trade.executedAt.desc(), trade.id.desc())
			.limit(fetchSize)
			.fetch();
	}
}
