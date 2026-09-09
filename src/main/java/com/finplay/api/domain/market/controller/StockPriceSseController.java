package com.finplay.api.domain.market.controller;

import com.finplay.api.domain.market.service.StockPriceStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockPriceSseController {

	private final StockPriceStreamService stockPriceStreamService;

	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		SseEmitter emitter = stockPriceStreamService.createEmitter();
		stockPriceStreamService.sendSnapshot(emitter);
		stockPriceStreamService.activate(emitter);
		return emitter;
	}
}
