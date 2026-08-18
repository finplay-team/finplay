// 게시물 좋아요 표시·취소 요청을 인증 사용자와 함께 서비스로 전달하는 컨트롤러
package com.finplay.api.community.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.community.service.CommunityPostLikeOutcome;
import com.finplay.api.community.service.CommunityPostLikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/posts/{postId}/likes")
@RequiredArgsConstructor
public class CommunityPostLikeController {

	private final CommunityPostLikeService communityPostLikeService;

	@PostMapping
	public ResponseEntity<CommunityPostLikeResponse> likePost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long postId) {
		CommunityPostLikeOutcome outcome = communityPostLikeService.likePost(postId, principal.userId());
		HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(outcome.response());
	}

	@DeleteMapping
	public ResponseEntity<Void> unlikePost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long postId) {
		communityPostLikeService.unlikePost(postId, principal.userId());
		return ResponseEntity.noContent().build();
	}
}
