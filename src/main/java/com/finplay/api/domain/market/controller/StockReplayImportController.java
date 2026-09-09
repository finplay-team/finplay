package com.finplay.api.domain.market.controller;

import com.finplay.api.domain.market.dto.response.StockReplayImportTriggerResponse;
import com.finplay.api.domain.market.service.StockReplayImportTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/stock-replay-imports")
@Profile("local")
@RequiredArgsConstructor
public class StockReplayImportController {

	private final StockReplayImportTriggerService stockReplayImportTriggerService;

	@PostMapping
	public ResponseEntity<StockReplayImportTriggerResponse> createStockReplayImport() {
		return ResponseEntity.ok(stockReplayImportTriggerService.trigger());
	}
}
