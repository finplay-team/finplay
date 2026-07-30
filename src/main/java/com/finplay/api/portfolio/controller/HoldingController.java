// 시장별 보유 종목 목록 조회를 담당하는 컨트롤러
package com.finplay.api.portfolio.controller;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.service.HoldingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class HoldingController {

	private final HoldingService holdingService;

	@GetMapping
	public ResponseEntity<List<HoldingListItemResponse>> getHoldings(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market) {
		return ResponseEntity.ok(holdingService.getHoldings(principal.userId(), market));
	}
}
