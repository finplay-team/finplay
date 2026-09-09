package com.finplay.api.domain.community.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.community.dto.request.PostCommentCreateRequest;
import com.finplay.api.domain.community.dto.response.PostCommentResponse;
import com.finplay.api.domain.community.service.PostCommentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

	@GetMapping
	public ResponseEntity<List<PostCommentResponse>> getComments(
		@PathVariable
		Long postId) {
		return ResponseEntity.ok(postCommentService.getComments(postId));
	}

	@PostMapping
	public ResponseEntity<PostCommentResponse> createComment(
		@PathVariable
		Long postId,
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PostCommentCreateRequest request) {
		PostCommentResponse response = postCommentService.createComment(
			postId, principal.userId(), request.content(), request.parentCommentId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
