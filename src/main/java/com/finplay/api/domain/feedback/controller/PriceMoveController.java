// 종목별 변동 원인 카드 목록 조회 요청을 처리하는 컨트롤러
package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.feedback.service.PriceMoveQueryService;

import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// URL 접두사는 market의 InstrumentController와 같지만 소유 도메인은 feedback이다 (spec §C-6) — 종목 축의
// 리소스라 경로를 나누지 않고, 클래스는 도메인 패키지에 둔다.
//
// 인증은 SecurityConfig의 anyRequest().authenticated()가 건다. 공개 GET 목록에 넣지 않으므로 토큰 없이
// 호출하면 401 UNAUTHORIZED다 (docs/api/feedback.md).
@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class PriceMoveController {

	private final PriceMoveQueryService priceMoveQueryService;

	/**
	 * 종목의 변동 원인 카드를 조회한다. 노출 시각이 지난 카드만 내려간다 (spec §C-5).
	 *
	 * <p>카드가 없거나 재생세션이 준비되지 않아도 <b>200</b>이다 — 오류가 아니다(FEED-006). 없는 종목만
	 * 404이며 그 판정은 서비스가 한다.
	 */
	@GetMapping("/{instrumentId}/price-moves")
	public ResponseEntity<PriceMoveListResponse> getPriceMoves(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(priceMoveQueryService.getPriceMoves(instrumentId));
	}
}
