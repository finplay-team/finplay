// 본인 댓글 삭제 요청을 인증 사용자와 함께 서비스로 전달하는 컨트롤러
package com.finplay.api.community.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.community.service.PostCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/comments")
@RequiredArgsConstructor
public class CommentController {

	private final PostCommentService postCommentService;

	@DeleteMapping("/{commentId}")
	public ResponseEntity<Void> deleteComment(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long commentId) {
		postCommentService.deleteComment(principal.userId(), commentId);
		return ResponseEntity.noContent().build();
	}
}
