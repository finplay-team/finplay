package com.finplay.api.domain.market.controller;

import com.finplay.api.domain.market.dto.response.StockDailyImportTriggerResponse;
import com.finplay.api.domain.market.service.StockDailyImportTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/stock-daily-imports")
@Profile("local")
@RequiredArgsConstructor
public class StockDailyImportTriggerController {

	private final StockDailyImportTriggerService stockDailyImportTriggerService;

	@PostMapping
	public ResponseEntity<StockDailyImportTriggerResponse> createStockDailyImport() {
		return ResponseEntity.ok(stockDailyImportTriggerService.trigger());
	}
}
