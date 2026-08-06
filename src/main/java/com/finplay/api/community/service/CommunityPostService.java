// 인증 사용자를 작성자로 연결해 커뮤니티 게시글 생성을 처리하는 서비스
package com.finplay.api.community.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.dto.response.CommunityPostListResponse;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
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
	private final PostCommentRepository postCommentRepository;
	private final UserQueryService userQueryService;
	private final Clock clock;

	@Transactional
	public CommunityPostResponse createPost(Long authenticatedUserId, String title, String content) {
		User author = userQueryService.getUser(authenticatedUserId);
		LocalDateTime now = LocalDateTime.now(clock);
		CommunityPost post = CommunityPost.create(author, title, content, null, now);
		return CommunityPostResponse.from(communityPostRepository.save(post));
	}

	@Transactional(readOnly = true)
	public CommunityPostResponse getPost(Long postId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return CommunityPostResponse.from(post);
	}

	@Transactional
	public CommunityPostResponse updatePost(Long authenticatedUserId, Long postId, String title, String content) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!post.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		post.update(title, content, post.getInstrument(), LocalDateTime.now(clock));
		return CommunityPostResponse.from(post);
	}

	@Transactional
	public void deletePost(Long authenticatedUserId, Long postId) {
		CommunityPost post = communityPostRepository.findById(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!post.getAuthor().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		postCommentRepository.deleteByPost_Id(postId);
		communityPostRepository.delete(post);
	}

	@Transactional(readOnly = true)
	public CommunityPostListResponse getPosts(int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		Page<CommunityPost> posts = communityPostRepository.findPostsOrderByCreatedAtDesc(pageable);
		return CommunityPostListResponse.from(posts);
	}
}
