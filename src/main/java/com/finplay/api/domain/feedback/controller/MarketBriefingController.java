package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.service.MarketBriefingService;
import com.finplay.api.domain.market.entity.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketBriefingController {

	private final MarketBriefingService marketBriefingService;

	@GetMapping("/briefing")
	public ResponseEntity<MarketBriefingResponse> getBriefing(
		@RequestParam
		Market market) {
		return ResponseEntity.ok(marketBriefingService.getBriefing(market));
	}
}
