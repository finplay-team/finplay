// 인증 사용자를 작성자로 연결해 커뮤니티 게시글 생성을 처리하는 서비스
package com.finplay.api.community.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.community.dto.response.CommunityPostListResponse;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.repository.CommunityPostLikeRepository;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import com.finplay.api.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.feedback.service.PostSellFeedbackService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
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
		// 사진과 매매 카드는 한 게시물에 함께 못 붙는다(TRADESHARE-004) — DB 조회 전에 먼저 거른다.
		if (imageId != null && sharedTradeId != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이미지와 매매 카드는 같은 게시물에 함께 첨부할 수 없습니다.");
		}
		User author = userQueryService.getUser(authenticatedUserId);
		Instrument instrument = resolveInstrument(instrumentId);
		CommunityPostImage image = imageId == null
			? null
			: communityPostImageService.resolveImageForPost(authenticatedUserId, imageId);
		// 소유권·side=SELL 검증은 여기서 1회만 한다(spec 046 "비즈니스 규칙") — 성공하면 그 결과를 응답에도 그대로
		// 재사용해 같은 계산을 두 번 하지 않는다.
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
		// 방금 만든 게시물이라 좋아요가 있을 수 없다 — 조회 없이 항상 false.
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

	// sharedTradeId가 없는 게시물이 대부분이라 이 경우에만 PostSellFeedbackService를 부른다 — 원장은 불변이라
	// 생성 시 검증된 값을 조회 시점에 재검증하지 않는다(spec 046 "비즈니스 규칙").
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
		// V31에서 post_comments의 parent_comment_id FK가 ON DELETE RESTRICT로 바뀌어, 자식(대댓글)을
		// 먼저 지우고 부모를 나중에 지워야 한다(순서를 바꾸면 FK 위반) — PostCommentRepository 참고.
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
		// findLikedPostIds는 빈 목록으로 호출하지 않는다(BuyTradeJournalRepository.findAllByBuyTradeIdIn과
		// 동일 관례) — 게시물이 없는 페이지는 조회 없이 빈 Set으로 처리한다.
		Set<Long> likedPostIds = postIds.isEmpty()
			? Set.of()
			: Set.copyOf(communityPostLikeRepository.findLikedPostIds(authenticatedUserId, postIds));
		// sharedTrade는 게시물별로 다른 tradeId를 조회해야 해서(TRADESHARE-002) likedPostIds처럼 배치 조회할 수
		// 없다 — resolveSharedTrade가 sharedTradeId 있는 게시물에서만 개별 호출한다(대부분 null이라 호출 자체가 없다).
		List<CommunityPostResponse> content = posts.getContent().stream()
			.map(post -> CommunityPostResponse.of(post, likedPostIds.contains(post.getId()), resolveSharedTrade(post)))
			.toList();
		return CommunityPostListResponse.of(content, posts);
	}
}
