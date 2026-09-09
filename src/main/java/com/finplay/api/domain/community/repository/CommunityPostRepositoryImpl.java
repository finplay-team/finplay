package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.auth.entity.QUser;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.QCommunityPost;
import com.finplay.api.domain.community.entity.QCommunityPostImage;
import com.finplay.api.domain.market.entity.QInstrument;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class CommunityPostRepositoryImpl implements CommunityPostRepositoryCustom {

	private static final String SORT_POPULAR = "popular";

	private final JPAQueryFactory queryFactory;

	public CommunityPostRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public Page<CommunityPost> findPosts(Pageable pageable, Long instrumentId, String sort) {
		QCommunityPost post = QCommunityPost.communityPost;
		QUser author = QUser.user;
		QInstrument instrument = QInstrument.instrument;
		QCommunityPostImage image = QCommunityPostImage.communityPostImage;

		BooleanExpression instrumentCondition = instrumentId == null
			? null
			: post.instrument.id.eq(instrumentId);

		OrderSpecifier<?>[] orderSpecifiers = SORT_POPULAR.equals(sort)
			? new OrderSpecifier<?>[] {post.likeCount.desc(), post.createdAt.desc(), post.id.desc()}
			: new OrderSpecifier<?>[] {post.createdAt.desc(), post.id.desc()};

		List<CommunityPost> content = queryFactory
			.selectFrom(post)
			.join(post.author, author).fetchJoin()
			.leftJoin(post.instrument, instrument).fetchJoin()
			.leftJoin(post.image, image).fetchJoin()
			.where(instrumentCondition)
			.orderBy(orderSpecifiers)
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
