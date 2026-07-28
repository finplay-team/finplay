// 시장 필터 종목 목록 조회 요청을 처리하는 컨트롤러
package com.finplay.api.market.controller;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.service.InstrumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
		String market) {
		return ResponseEntity.ok(instrumentService.getInstruments(parseMarket(market)));
	}

	private Market parseMarket(String market) {
		if (market == null || market.isBlank()) {
			return null;
		}
		try {
			return Market.valueOf(market);
		} catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "market은 STOCK 또는 CRYPTO만 가능합니다.");
		}
	}
}
