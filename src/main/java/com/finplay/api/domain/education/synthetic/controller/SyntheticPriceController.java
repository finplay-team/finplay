package com.finplay.api.domain.education.synthetic.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.domain.education.synthetic.service.SyntheticPriceService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/synthetic-prices")
@RequiredArgsConstructor
@Validated
public class SyntheticPriceController {

	private final SyntheticPriceService syntheticPriceService;

	@GetMapping("/{instrumentId}")
	public ResponseEntity<SyntheticPriceSeriesResponse> getSyntheticPrices(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable @Positive(message = "종목 ID는 양수여야 합니다.")
		Long instrumentId) {
		return ResponseEntity.ok(syntheticPriceService.generateSeries(instrumentId));
	}
}
