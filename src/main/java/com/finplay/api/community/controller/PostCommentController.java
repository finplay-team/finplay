// 커뮤니티 게시글 댓글 생성 요청을 검증하고 인증 사용자와 함께 서비스로 전달하는 컨트롤러
package com.finplay.api.community.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.community.dto.request.PostCommentCreateRequest;
import com.finplay.api.community.dto.response.PostCommentResponse;
import com.finplay.api.community.service.PostCommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/posts/{postId}/comments")
@RequiredArgsConstructor
public class PostCommentController {

	private final PostCommentService postCommentService;

	@PostMapping
	public ResponseEntity<PostCommentResponse> createComment(
		@PathVariable
		Long postId,
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PostCommentCreateRequest request) {
		PostCommentResponse response = postCommentService.createComment(postId, principal.userId(), request.content());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
