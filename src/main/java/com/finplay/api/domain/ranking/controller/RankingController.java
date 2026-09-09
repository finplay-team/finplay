package com.finplay.api.domain.ranking.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

	private final RankingService rankingService;

	@GetMapping
	public ResponseEntity<RankingListResponse> getRankings(
		@RequestParam
		Market market,
		@RequestParam(required = false)
		Integer limit) {
		return ResponseEntity.ok(rankingService.getRankings(market, limit));
	}

	@GetMapping("/me")
	public ResponseEntity<MyRankingResponse> getMyRanking(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market) {
		return ResponseEntity.ok(rankingService.getMyRanking(principal.userId(), market));
	}
}
