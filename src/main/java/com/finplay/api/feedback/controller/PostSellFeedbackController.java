// 본인 매도 체결 1건의 매도 직후 피드백 조회 요청을 처리하는 컨트롤러
package com.finplay.api.feedback.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.service.PostSellFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 경로는 새로 만들지 않는다 — Notion api 명세서 §6의 GET /ai/post-sell/{id}에 레포 Base URL 규칙(/api)만
// 적용했다(spec FEED-007, docs/api-routes.md).
//
// 인증은 SecurityConfig의 anyRequest().authenticated()가 건다. 공개 GET 목록에 넣지 않으므로 토큰 없이
// 호출하면 401 UNAUTHORIZED다 (docs/api-contracts.md).
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class PostSellFeedbackController {

	private final PostSellFeedbackService postSellFeedbackService;

	/**
	 * 매도 체결 1건의 회고를 조회한다. <b>조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유
	 * 매도 체결로만 결정한다</b>({@code docs/api-contracts.md}).
	 *
	 * <p>미존재 404·타인 체결 403·매수 체결 400·코인 체결 400의 판정은 모두 서비스가 한다 — 컨트롤러는
	 * 비즈니스 판단을 하지 않는다({@code docs/conventions.md}).
	 */
	@GetMapping("/post-sell/{tradeId}")
	public ResponseEntity<PostSellFeedbackResponse> getPostSellFeedback(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long tradeId) {
		return ResponseEntity.ok(postSellFeedbackService.getPostSellFeedback(principal.userId(), tradeId));
	}
}
