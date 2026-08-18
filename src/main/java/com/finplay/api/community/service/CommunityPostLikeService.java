// 게시물 좋아요 표시·취소를 멱등하게 처리하는 서비스
package com.finplay.api.community.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostLike;
import com.finplay.api.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.community.repository.CommunityPostLikeRepository;
import com.finplay.api.community.repository.CommunityPostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityPostLikeService {

	private final CommunityPostRepository communityPostRepository;
	private final CommunityPostLikeRepository communityPostLikeRepository;
	private final UserQueryService userQueryService;
	private final Clock clock;

	// 본인 게시물에도 좋아요를 허용한다(spec 045 비즈니스 규칙) — 별도 차단 로직을 두지 않는다.
	@Transactional
	public CommunityPostLikeOutcome likePost(Long postId, Long authenticatedUserId) {
		if (!communityPostRepository.existsById(postId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		// 좋아요 응답에는 author·instrument·image가 필요 없다 — findById의 @EntityGraph 즉시 로딩(리뷰 참고)을
		// 피하려고 참조만 얻는다. likeCount 필드 접근 시 그 컬럼만 있는 select 1회가 나간다.
		CommunityPost post = communityPostRepository.getReferenceById(postId);

		boolean alreadyLiked = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		if (alreadyLiked) {
			CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount(), true);
			return new CommunityPostLikeOutcome(response, false);
		}

		User user = userQueryService.getUser(authenticatedUserId);
		try {
			communityPostLikeRepository.saveAndFlush(CommunityPostLike.create(post, user, LocalDateTime.now(clock)));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			// 동시에 같은 사용자가 두 번 좋아요를 눌러도 유니크 제약이 정합성을 지킨다 — 두 번째 요청은
			// 에러가 아니라 이미 좋아요된 현재 상태로 응답한다(LIKE-001 멱등 요구, WatchlistService 선례와 동일 패턴).
			CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount(), true);
			return new CommunityPostLikeOutcome(response, false);
		}
		// post는 getReferenceById로 얻은 지연 프록시라, 값을 먼저 읽어 초기화해 둔다 — incrementLikeCount의
		// clearAutomatically가 영속성 컨텍스트를 비운 뒤에 접근하면 LazyInitializationException이 난다.
		long likeCountBeforeIncrement = post.getLikeCount();
		communityPostRepository.incrementLikeCount(postId);

		CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, likeCountBeforeIncrement + 1, true);
		return new CommunityPostLikeOutcome(response, true);
	}

	@Transactional
	public void unlikePost(Long postId, Long authenticatedUserId) {
		if (!communityPostRepository.existsById(postId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}

		communityPostLikeRepository.findByPost_IdAndUser_Id(postId, authenticatedUserId)
			.ifPresent(like -> {
				communityPostLikeRepository.delete(like);
				communityPostRepository.decrementLikeCount(postId);
			});
	}
}
