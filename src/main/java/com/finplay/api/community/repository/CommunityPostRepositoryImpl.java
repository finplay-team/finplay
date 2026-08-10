// 게시물 목록을 작성자와 함께 최신순으로 페이지네이션 조회하는 QueryDSL 구현체
package com.finplay.api.community.repository;

import com.finplay.api.auth.domain.QUser;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.QCommunityPost;
import com.finplay.api.community.domain.QCommunityPostImage;
import com.finplay.api.market.domain.QInstrument;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class CommunityPostRepositoryImpl implements CommunityPostRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public CommunityPostRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public Page<CommunityPost> findPostsOrderByCreatedAtDesc(Pageable pageable, Long instrumentId) {
		QCommunityPost post = QCommunityPost.communityPost;
		QUser author = QUser.user;
		QInstrument instrument = QInstrument.instrument;
		QCommunityPostImage image = QCommunityPostImage.communityPostImage;

		BooleanExpression instrumentCondition = instrumentId == null
			? null
			: post.instrument.id.eq(instrumentId);

		List<CommunityPost> content = queryFactory
			.selectFrom(post)
			.join(post.author, author).fetchJoin()
			.leftJoin(post.instrument, instrument).fetchJoin()
			.leftJoin(post.image, image).fetchJoin()
			.where(instrumentCondition)
			.orderBy(post.createdAt.desc(), post.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		Long fetchedTotalElements = queryFactory
			.select(post.count())
			.from(post)
			.where(instrumentCondition)
			.fetchOne();
		long totalElements = fetchedTotalElements == null ? 0L : fetchedTotalElements;

		return new PageImpl<>(content, pageable, totalElements);
	}
}
