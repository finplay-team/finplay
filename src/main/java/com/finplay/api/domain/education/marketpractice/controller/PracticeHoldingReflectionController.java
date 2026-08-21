// 인증 사용자의 실습 3단계 자유 복기 생성(완료 확정) 요청을 처리하는 컨트롤러
package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeHoldingReflectionService;
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
@RequestMapping("/api/education/practice/holding-reflections")
@RequiredArgsConstructor
public class PracticeHoldingReflectionController {

	private final PracticeHoldingReflectionService practiceHoldingReflectionService;

	@PostMapping
	public ResponseEntity<PracticeHoldingReflectionResponse> createReflection(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PracticeHoldingReflectionCreateRequest request) {
		PracticeHoldingReflectionResponse response = practiceHoldingReflectionService.createReflection(
			principal.userId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
