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
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		boolean alreadyLiked = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		if (alreadyLiked) {
			CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount(), true);
			return new CommunityPostLikeOutcome(response, false);
		}

		User user = userQueryService.getUser(authenticatedUserId);
		communityPostLikeRepository.save(CommunityPostLike.create(post, user, LocalDateTime.now(clock)));
		communityPostRepository.incrementLikeCount(postId);

		// incrementLikeCount는 clearAutomatically로 영속성 컨텍스트를 비운다 — post는 이미 읽어 둔 값이므로
		// 재조회 없이 +1로 직접 계산한다.
		CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount() + 1, true);
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
