// 인증 사용자의 코인 가상 가격 세션 귀속 교육 지정가 BUY 주문 생성 요청을 처리하는 컨트롤러
package com.finplay.api.domain.education.priceruntime.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.service.PracticeLimitOrderService;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/limit-orders")
@RequiredArgsConstructor
@Validated
public class PracticeLimitOrderController {

	private final PracticeLimitOrderService practiceLimitOrderService;

	@PostMapping
	public ResponseEntity<LimitOrderResponse> createOrder(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestBody @Valid
		PracticeLimitOrderCreateRequest request) {
		LimitOrderResponse response = practiceLimitOrderService.createOrder(principal.userId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
