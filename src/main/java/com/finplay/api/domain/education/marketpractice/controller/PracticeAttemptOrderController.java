package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptOrderQueryService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
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
