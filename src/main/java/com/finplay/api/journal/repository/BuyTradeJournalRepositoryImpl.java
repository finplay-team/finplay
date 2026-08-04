// 계좌 단위 매수 투자일기를 커서 페이지네이션으로 조회하는 QueryDSL 구현체
package com.finplay.api.journal.repository;

import com.finplay.api.journal.domain.BuyTradeJournal;
import com.finplay.api.journal.domain.QBuyTradeJournal;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;

public class BuyTradeJournalRepositoryImpl implements BuyTradeJournalRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public BuyTradeJournalRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<BuyTradeJournal> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorCreatedAt, Long cursorTradeId, int fetchSize) {
		QBuyTradeJournal journal = QBuyTradeJournal.buyTradeJournal;

		BooleanBuilder condition = new BooleanBuilder(journal.buyTrade.account.id.eq(accountId));
		if (cursorCreatedAt != null && cursorTradeId != null) {
			condition.and(
				journal.createdAt.lt(cursorCreatedAt)
					.or(journal.createdAt.eq(cursorCreatedAt).and(journal.buyTrade.id.lt(cursorTradeId))));
		}

		return queryFactory
			.selectFrom(journal)
			.join(journal.buyTrade).fetchJoin()
			.where(condition)
			.orderBy(journal.createdAt.desc(), journal.buyTrade.id.desc())
			.limit(fetchSize)
			.fetch();
	}
}
