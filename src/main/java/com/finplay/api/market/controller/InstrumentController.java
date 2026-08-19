// 시장 필터 종목 목록 조회 요청을 처리하는 컨트롤러
package com.finplay.api.market.controller;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.dto.response.PriceResponse;
import com.finplay.api.market.service.CandleQueryService;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.PriceQueryService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentController {

	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;
	private final CandleQueryService candleQueryService;

	@GetMapping
	public ResponseEntity<List<InstrumentResponse>> getInstruments(
		@RequestParam(required = false)
		Market market) {
		return ResponseEntity.ok(instrumentService.getInstruments(market));
	}

	@GetMapping("/{instrumentId}/price")
	public ResponseEntity<PriceResponse> getPrice(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(PriceResponse.from(priceQueryService.getPrice(instrumentId)));
	}

	@GetMapping("/{instrumentId}/candles")
	public ResponseEntity<List<CandleResponse>> getCandles(
		@PathVariable
		Long instrumentId,
		@RequestParam
		String interval,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime to) {
		return ResponseEntity.ok(candleQueryService.getCandles(instrumentId, interval, from, to));
	}
}
