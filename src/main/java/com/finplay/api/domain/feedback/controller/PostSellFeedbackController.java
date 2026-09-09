package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.service.PostSellFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class PostSellFeedbackController {

	private final PostSellFeedbackService postSellFeedbackService;

	@GetMapping("/post-sell/{tradeId}")
	public ResponseEntity<PostSellFeedbackResponse> getPostSellFeedback(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long tradeId) {
		return ResponseEntity.ok(postSellFeedbackService.getPostSellFeedback(principal.userId(), tradeId));
	}
}
