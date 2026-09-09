package com.finplay.api.domain.community.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostLike;
import com.finplay.api.domain.community.repository.CommunityPostLikeRepository;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

	@Transactional
	public CommunityPostLikeOutcome likePost(Long postId, Long authenticatedUserId) {
		CommunityPost post = communityPostRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		boolean alreadyLiked = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		if (alreadyLiked) {
			CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount(), true);
			return new CommunityPostLikeOutcome(response, false);
		}

		User user = userQueryService.getUser(authenticatedUserId);
		communityPostLikeRepository.saveAndFlush(CommunityPostLike.create(post, user, LocalDateTime.now(clock)));
		long likeCountBeforeIncrement = post.getLikeCount();
		communityPostRepository.incrementLikeCount(postId);

		CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, likeCountBeforeIncrement + 1, true);
		return new CommunityPostLikeOutcome(response, true);
	}

	@Transactional
	public void unlikePost(Long postId, Long authenticatedUserId) {
		communityPostRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		communityPostLikeRepository.findByPost_IdAndUser_Id(postId, authenticatedUserId)
			.ifPresent(like -> {
				communityPostLikeRepository.delete(like);
				communityPostRepository.decrementLikeCount(postId);
			});
	}
}
