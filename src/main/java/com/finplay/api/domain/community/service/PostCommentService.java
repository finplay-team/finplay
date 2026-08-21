// 게시글과 인증 사용자를 연결해 커뮤니티 댓글을 생성하는 서비스
package com.finplay.api.domain.community.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.PostComment;
import com.finplay.api.domain.community.dto.response.PostCommentResponse;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.community.repository.PostCommentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostCommentService {

	private final CommunityPostRepository communityPostRepository;
	private final PostCommentRepository postCommentRepository;
	private final UserQueryService userQueryService;
	private final Clock clock;

	@Transactional
	public PostCommentResponse createComment(
		Long postId, Long authenticatedUserId, String content, Long parentCommentId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		User author = userQueryService.getUser(authenticatedUserId);
		PostComment parentComment = null;
		if (parentCommentId != null) {
			parentComment = postCommentRepository.findById(parentCommentId)
				.filter(candidate -> candidate.getPost().getId().equals(postId))
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
			if (parentComment.isReply()) {
				throw new BusinessException(
					ErrorCode.VALIDATION_ERROR, "대댓글에는 답글을 남길 수 없습니다.");
			}
			if (parentComment.isTombstoned()) {
				throw new BusinessException(
					ErrorCode.VALIDATION_ERROR, "삭제된 댓글에는 답글을 남길 수 없습니다.");
			}
		}
		LocalDateTime now = LocalDateTime.now(clock);
		PostComment comment = PostComment.create(post, author, content, parentComment, now);
		return PostCommentResponse.from(postCommentRepository.save(comment));
	}

	@Transactional
	public void deleteComment(Long authenticatedUserId, Long commentId) {
		PostComment comment = postCommentRepository.findById(commentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!comment.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (comment.getParentComment() == null) {
			comment.tombstone(LocalDateTime.now(clock));
		} else {
			postCommentRepository.delete(comment);
		}
	}

	@Transactional(readOnly = true)
	public List<PostCommentResponse> getComments(Long postId) {
		if (!communityPostRepository.existsById(postId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		List<PostComment> allComments = postCommentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc(postId);
		Map<Long, List<PostComment>> repliesByParentId = new HashMap<>();
		List<PostComment> topLevelComments = new ArrayList<>();
		for (PostComment comment : allComments) {
			if (comment.isReply()) {
				repliesByParentId
					.computeIfAbsent(comment.getParentComment().getId(), key -> new ArrayList<>())
					.add(comment);
			} else {
				topLevelComments.add(comment);
			}
		}
		List<PostCommentResponse> result = new ArrayList<>();
		for (PostComment parent : topLevelComments) {
			List<PostCommentResponse> replies = repliesByParentId.getOrDefault(parent.getId(), List.of())
				.stream()
				.map(PostCommentResponse::from)
				.toList();
			result.add(PostCommentResponse.from(parent, replies));
		}
		return result;
	}
}
