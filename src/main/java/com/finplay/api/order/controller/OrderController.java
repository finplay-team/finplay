// 시장가 매수 주문 생성 요청을 받아 인증 사용자 ID로 서비스에 전달하는 컨트롤러
package com.finplay.api.order.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

	private final OrderService orderService;

	@PostMapping
	public ResponseEntity<OrderResponse> createOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100)
		String idempotencyKey,
		@Valid @RequestBody
		OrderCreateRequest request) {
		OrderResponse response = orderService.createBuyOrder(principal.userId(), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	public ResponseEntity<List<OrderListItemResponse>> getMyOrders(
		@AuthenticationPrincipal
		AuthenticatedUser principal) {
		return ResponseEntity.ok(orderService.getMyOrders(principal.userId()));
	}
}
