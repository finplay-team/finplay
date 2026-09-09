package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentNewsController {

	private final InstrumentNewsQueryService instrumentNewsQueryService;

	@GetMapping("/{instrumentId}/news")
	public ResponseEntity<InstrumentNewsResponse> getInstrumentNews(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(instrumentNewsQueryService.getInstrumentNews(instrumentId));
	}
}
