package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.domain.feedback.service.PriceMoveQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class PriceMoveController {

	private final PriceMoveQueryService priceMoveQueryService;

	@GetMapping("/{instrumentId}/price-moves")
	public ResponseEntity<PriceMoveListResponse> getPriceMoves(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(priceMoveQueryService.getPriceMoves(instrumentId));
	}
}
