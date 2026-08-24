// 인증 사용자의 시장별 3단계 투자 실습 진행 상태 순수 조회 요청을 처리하는 컨트롤러
package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.service.InvestmentPracticeQueryService;
import com.finplay.api.domain.market.entity.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice")
@RequiredArgsConstructor
public class InvestmentPracticeController {

	private final InvestmentPracticeQueryService investmentPracticeQueryService;

	@GetMapping
	public ResponseEntity<InvestmentPracticeResponse> getProgress(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market) {
		return ResponseEntity.ok(investmentPracticeQueryService.getProgress(principal.userId(), market));
	}
}
