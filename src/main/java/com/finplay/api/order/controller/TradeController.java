// 본인의 시장별 체결 내역을 커서 페이지네이션으로 조회하는 컨트롤러
package com.finplay.api.order.controller;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.dto.response.TradeListResponse;
import com.finplay.api.order.service.TradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 100;

	private final TradeService tradeService;

	@GetMapping
	public ResponseEntity<TradeListResponse> getMyTrades(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market,
		@RequestParam(required = false)
		String cursor,
		@RequestParam(defaultValue = "" + DEFAULT_LIMIT)
		int limit) {
		validateLimit(limit);
		return ResponseEntity.ok(tradeService.getMyTrades(principal.userId(), market, cursor, limit));
	}

	private void validateLimit(int limit) {
		if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~100 사이여야 합니다.");
		}
	}
}
