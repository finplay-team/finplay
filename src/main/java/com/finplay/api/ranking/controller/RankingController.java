// 시장별 실현손익 전체 랭킹을 조회하는 컨트롤러
package com.finplay.api.ranking.controller;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.ranking.dto.response.MyRankingResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.service.RankingService;
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

	// limit은 여기서 클램핑·검증하지 않고 그대로 서비스에 전달한다 — 0 이하·51 이상도 400이 아니라
	// RankingService가 클램핑한다(GET /api/trades·GET /api/orders의 validateLimit과 의도적으로 다름, plan.md 187행).
	@GetMapping
	public ResponseEntity<RankingListResponse> getRankings(
		@RequestParam
		Market market,
		@RequestParam(required = false)
		Integer limit) {
		return ResponseEntity.ok(rankingService.getRankings(market, limit));
	}

	// 인증 사용자 본인의 시장별 순위만 반환한다(RANK-002) — 다른 사용자를 지정하는 파라미터는 받지 않는다.
	// AccountController.getAccountSummary와 동일한 인증 패턴: @AuthenticationPrincipal + principal.userId().
	@GetMapping("/me")
	public ResponseEntity<MyRankingResponse> getMyRanking(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market) {
		return ResponseEntity.ok(rankingService.getMyRanking(principal.userId(), market));
	}
}
