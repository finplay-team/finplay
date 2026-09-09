package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.QSellTradeJournal;
import com.finplay.api.domain.journal.entity.SellTradeJournal;
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

		BooleanBuilder condition = new BooleanBuilder(
			journal.sellTrade.account.id.eq(accountId).and(journal.sellTrade.instrument.tutorialSample.eq(false)));
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
