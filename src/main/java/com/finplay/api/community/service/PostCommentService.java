// 게시글과 인증 사용자를 연결해 커뮤니티 댓글을 생성하는 서비스
package com.finplay.api.community.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.PostComment;
import com.finplay.api.community.dto.response.PostCommentResponse;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
	public PostCommentResponse createComment(Long postId, Long authenticatedUserId, String content) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		User author = userQueryService.getUser(authenticatedUserId);
		LocalDateTime now = LocalDateTime.now(clock);
		PostComment comment = PostComment.create(post, author, content, null, now);
		return PostCommentResponse.from(postCommentRepository.save(comment));
	}

	@Transactional
	public void deleteComment(Long authenticatedUserId, Long commentId) {
		PostComment comment = postCommentRepository.findById(commentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!comment.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		postCommentRepository.delete(comment);
	}

	@Transactional(readOnly = true)
	public List<PostCommentResponse> getComments(Long postId) {
		if (!communityPostRepository.existsById(postId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return postCommentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc(postId)
			.stream()
			.map(PostCommentResponse::from)
			.toList();
	}
}
