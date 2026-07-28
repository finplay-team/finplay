// 시장 필터 종목 목록 조회 요청을 처리하는 컨트롤러
package com.finplay.api.market.controller;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.service.InstrumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

	@GetMapping
	public ResponseEntity<List<InstrumentResponse>> getInstruments(
		@RequestParam(required = false)
		Market market) {
		return ResponseEntity.ok(instrumentService.getInstruments(market));
	}

	@GetMapping("/{instrumentId}")
	public ResponseEntity<InstrumentResponse> getInstrument(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(instrumentService.getInstrument(instrumentId));
	}
}
