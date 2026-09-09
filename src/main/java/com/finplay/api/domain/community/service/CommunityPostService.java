package com.finplay.api.domain.community.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.community.dto.response.CommunityPostListResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostResponse;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostImage;
import com.finplay.api.domain.community.repository.CommunityPostLikeRepository;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.community.repository.PostCommentRepository;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.feedback.service.PostSellFeedbackService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityPostService {

	private final CommunityPostRepository communityPostRepository;
	private final CommunityPostLikeRepository communityPostLikeRepository;
	private final PostCommentRepository postCommentRepository;
	private final UserQueryService userQueryService;
	private final InstrumentService instrumentService;
	private final CommunityPostImageService communityPostImageService;
	private final PostSellFeedbackService postSellFeedbackService;
	private final Clock clock;

	@Transactional
	public CommunityPostResponse createPost(
		Long authenticatedUserId, String title, String content, Long instrumentId, Long imageId,
		Long sharedTradeId) {
		if (imageId != null && sharedTradeId != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이미지와 매매 카드는 같은 게시물에 함께 첨부할 수 없습니다.");
		}
		User author = userQueryService.getUser(authenticatedUserId);
		Instrument instrument = resolveInstrument(instrumentId);
		CommunityPostImage image = imageId == null
			? null
			: communityPostImageService.resolveImageForPost(authenticatedUserId, imageId);
		TradeShareSummaryResponse sharedTrade = sharedTradeId == null
			? null
			: postSellFeedbackService.getTradeShareSummary(authenticatedUserId, sharedTradeId);
		LocalDateTime now = LocalDateTime.now(clock);
		CommunityPost post = CommunityPost.create(author, title, content, instrument, now);
		if (sharedTradeId != null) {
			post.attachSharedTrade(sharedTradeId);
		}
		CommunityPost savedPost = communityPostRepository.save(post);
		if (image != null) {
			image.assignToPost(savedPost);
			savedPost.attachImage(image);
		}
		return CommunityPostResponse.of(savedPost, false, sharedTrade);
	}

	@Transactional(readOnly = true)
	public CommunityPostResponse getPost(Long postId, Long authenticatedUserId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		boolean likedByMe = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		return CommunityPostResponse.of(post, likedByMe, resolveSharedTrade(post));
	}

	@Transactional
	public CommunityPostResponse updatePost(
		Long authenticatedUserId, Long postId, String title, String content, boolean instrumentIdProvided,
		Long instrumentId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!post.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		Instrument instrument = instrumentIdProvided ? resolveInstrument(instrumentId) : post.getInstrument();
		post.update(title, content, instrument, LocalDateTime.now(clock));
		boolean likedByMe = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		return CommunityPostResponse.of(post, likedByMe, resolveSharedTrade(post));
	}

	private TradeShareSummaryResponse resolveSharedTrade(CommunityPost post) {
		Long sharedTradeId = post.getSharedTradeId();
		return sharedTradeId == null
			? null
			: postSellFeedbackService.getTradeShareSummary(post.getAuthor().getId(), sharedTradeId);
	}

	private Instrument resolveInstrument(Long instrumentId) {
		if (instrumentId == null) {
			return null;
		}
		return instrumentService.getTradableInstrumentEntity(instrumentId);
	}

	@Transactional
	public void deletePost(Long authenticatedUserId, Long postId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!post.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		postCommentRepository.deleteByPost_IdAndParentCommentIsNotNull(postId);
		postCommentRepository.deleteByPost_IdAndParentCommentIsNull(postId);
		communityPostImageService.deleteImageIfPresent(post);
		communityPostRepository.delete(post);
	}

	@Transactional(readOnly = true)
	public CommunityPostListResponse getPosts(
		int page, int size, Long instrumentId, String sort, Long authenticatedUserId) {
		Pageable pageable = PageRequest.of(page, size);
		Page<CommunityPost> posts = communityPostRepository.findPosts(pageable, instrumentId, sort);
		List<Long> postIds = posts.getContent().stream().map(CommunityPost::getId).toList();
		Set<Long> likedPostIds = postIds.isEmpty()
			? Set.of()
			: Set.copyOf(communityPostLikeRepository.findLikedPostIds(authenticatedUserId, postIds));
		List<CommunityPostResponse> content = posts.getContent().stream()
			.map(post -> CommunityPostResponse.of(post, likedPostIds.contains(post.getId()), resolveSharedTrade(post)))
			.toList();
		return CommunityPostListResponse.of(content, posts);
	}
}
