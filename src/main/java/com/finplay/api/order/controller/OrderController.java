// 주문 생성·목록 조회를 담당하는 컨트롤러
package com.finplay.api.order.controller;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.dto.response.OrderListResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.service.LimitOrderCancelService;
import com.finplay.api.order.service.LimitOrderModifyService;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 100;

	private final OrderService orderService;
	private final LimitOrderService limitOrderService;
	private final LimitOrderCancelService limitOrderCancelService;
	private final LimitOrderModifyService limitOrderModifyService;

	@PostMapping
	public ResponseEntity<OrderResponse> createOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100)
		String idempotencyKey,
		@Valid @RequestBody
		OrderCreateRequest request) {
		OrderResponse response = orderService.createOrder(principal.userId(), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/limit")
	public ResponseEntity<LimitOrderResponse> createLimitOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100)
		String idempotencyKey,
		@Valid @RequestBody
		LimitOrderCreateRequest request) {
		LimitOrderResponse response = limitOrderService.createLimitOrder(principal.userId(), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@DeleteMapping("/{orderId}")
	public ResponseEntity<Void> cancelLimitOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long orderId) {
		limitOrderCancelService.cancelOrder(principal.userId(), orderId);
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{orderId}")
	public ResponseEntity<LimitOrderResponse> modifyLimitOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long orderId,
		@Valid @RequestBody
		LimitOrderUpdateRequest request) {
		LimitOrderResponse response = limitOrderModifyService.modifyOrder(principal.userId(), orderId, request);
		return ResponseEntity.ok(response);
	}

	@GetMapping
	public ResponseEntity<OrderListResponse> getMyOrders(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market,
		@RequestParam(required = false)
		String cursor,
		@RequestParam(defaultValue = "" + DEFAULT_LIMIT)
		int limit) {
		validateLimit(limit);
		return ResponseEntity.ok(orderService.getMyOrders(principal.userId(), market, cursor, limit));
	}

	@GetMapping("/pending")
	public ResponseEntity<OrderListResponse> getMyPendingOrders(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market,
		@RequestParam(required = false)
		String cursor,
		@RequestParam(defaultValue = "" + DEFAULT_LIMIT)
		int limit) {
		validateLimit(limit);
		return ResponseEntity.ok(orderService.getMyPendingOrders(principal.userId(), market, cursor, limit));
	}

	private void validateLimit(int limit) {
		if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~100 사이여야 합니다.");
		}
	}
}
