// 계좌 단위 매도 회고를 커서 페이지네이션으로 조회하는 QueryDSL 구현체
package com.finplay.api.journal.repository;

import com.finplay.api.journal.domain.QSellTradeJournal;
import com.finplay.api.journal.domain.SellTradeJournal;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

public class SellTradeJournalRepositoryImpl implements SellTradeJournalRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public SellTradeJournalRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<SellTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize) {
		QSellTradeJournal journal = QSellTradeJournal.sellTradeJournal;

		BooleanBuilder condition = new BooleanBuilder(journal.sellTrade.account.id.eq(accountId));
		if (cursorCreatedAt != null && cursorTradeId != null) {
			condition.and(
				journal.createdAt.lt(cursorCreatedAt)
					.or(journal.createdAt.eq(cursorCreatedAt).and(journal.sellTrade.id.lt(cursorTradeId))));
		}

		return queryFactory
			.selectFrom(journal)
			.join(journal.sellTrade).fetchJoin()
			.where(condition)
			.orderBy(journal.createdAt.desc(), journal.sellTrade.id.desc())
			.limit(fetchSize)
			.fetch();
	}
}
