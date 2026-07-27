// 게시물 목록을 작성자와 함께 최신순으로 페이지네이션 조회하는 QueryDSL 구현체
package com.finplay.api.community.repository;

import com.finplay.api.auth.domain.QUser;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.QCommunityPost;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@RequiredArgsConstructor
public class CommunityPostRepositoryImpl implements CommunityPostRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<CommunityPost> findPostsOrderByCreatedAtDesc(Pageable pageable) {
		QCommunityPost post = QCommunityPost.communityPost;
		QUser author = QUser.user;

		List<CommunityPost> content = queryFactory
			.selectFrom(post)
			.join(post.author, author).fetchJoin()
			.orderBy(post.createdAt.desc(), post.id.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize())
			.fetch();

		long totalElements = queryFactory
			.select(post.count())
			.from(post)
			.fetchOne();

		return new PageImpl<>(content, pageable, totalElements);
	}
}
