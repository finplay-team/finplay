// 시장 필터 종목 목록 조회 요청을 처리하는 컨트롤러
package com.finplay.api.domain.market.controller;

import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.InstrumentResponse;
import com.finplay.api.domain.market.dto.response.PriceResponse;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CandleQueryService;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
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
	public ResponseEntity<CandleListResponse> getCandles(
		@PathVariable
		Long instrumentId,
		@RequestParam
		String interval,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime from,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime to,
		// cursor는 @DateTimeFormat을 붙이지 않는다 — 바인더 단계 파싱 실패가 interval 400·종목 404보다 먼저
		// 터져 검증 순서(CANDLE-PAGE-010)를 깬다. 형식 검증은 서비스의 CandleCursor.parse가 담당한다(plan §3).
		@RequestParam(required = false)
		String cursor) {
		return ResponseEntity.ok(candleQueryService.getCandles(instrumentId, interval, from, to, cursor));
	}
}
