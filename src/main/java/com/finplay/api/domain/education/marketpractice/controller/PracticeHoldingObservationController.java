// 인증 사용자의 실습 3단계 가격 관찰 생성 요청을 처리하는 컨트롤러
package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeHoldingObservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/holding-observations")
@RequiredArgsConstructor
public class PracticeHoldingObservationController {

	private final PracticeHoldingObservationService practiceHoldingObservationService;

	@PostMapping
	public ResponseEntity<PracticeHoldingObservationResponse> createObservation(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PracticeHoldingObservationCreateRequest request) {
		PracticeHoldingObservationResponse response = practiceHoldingObservationService.createObservation(
			principal.userId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
