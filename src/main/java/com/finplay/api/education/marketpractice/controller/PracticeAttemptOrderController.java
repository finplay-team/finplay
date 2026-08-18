// 인증 사용자의 현재 튜토리얼 attempt·run 주문 목록 조회를 처리하는 컨트롤러
package com.finplay.api.education.marketpractice.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.marketpractice.service.PracticeAttemptOrderQueryService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/attempts")
@RequiredArgsConstructor
public class PracticeAttemptOrderController {

	private final PracticeAttemptOrderQueryService practiceAttemptOrderQueryService;

	@GetMapping("/{market}/orders")
	public ResponseEntity<List<OrderListItemResponse>> getOrders(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptOrderQueryService.getCurrentRunOrders(principal.userId(), market));
	}
}
