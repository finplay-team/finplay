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

		// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001과 동일 원칙, 이슈: 포트폴리오 체결 내역 누출) —
		// 이 조회는 GET /api/trades(사용자 노출) 전용이다. 튜토리얼 샌드박스 종목 체결은 실거래 화면에
		// 섞이면 안 된다.
		BooleanBuilder condition = new BooleanBuilder(trade.account.id.eq(accountId))
			.and(trade.instrument.tutorialSample.eq(false));
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
