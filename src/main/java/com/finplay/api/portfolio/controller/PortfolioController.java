// 주식·코인 계좌를 합산한 전체 포트폴리오 요약 조회를 담당하는 컨트롤러
package com.finplay.api.portfolio.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import com.finplay.api.portfolio.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

	private final PortfolioService portfolioService;

	@GetMapping
	public ResponseEntity<PortfolioSummaryResponse> getPortfolioSummary(
		@AuthenticationPrincipal
		AuthenticatedUser principal) {
		return ResponseEntity.ok(portfolioService.getPortfolioSummary(principal.userId()));
	}
}
