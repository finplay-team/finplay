// 인증 사용자의 튜토리얼 전용 합성 시세 조회 요청을 처리하는 컨트롤러
package com.finplay.api.education.synthetic.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.education.synthetic.service.SyntheticPriceService;
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
