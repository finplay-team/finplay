// 종목별 뉴스·공시 목록과 AI 요약 조회 요청을 처리하는 컨트롤러
package com.finplay.api.domain.feedback.controller;

import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// URL 접두사는 market의 InstrumentController와 같지만 소유 도메인은 feedback이다 (spec §C-6) — 종목 축의
// 리소스라 경로를 나누지 않고, 클래스는 도메인 패키지에 둔다. PriceMoveController와 같은 형태다.
//
// 인증은 SecurityConfig의 anyRequest().authenticated()가 건다. 공개 GET 목록에 넣지 않으므로 토큰 없이
// 호출하면 401 UNAUTHORIZED다 (docs/api/feedback.md).
@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentNewsController {

	private final InstrumentNewsQueryService instrumentNewsQueryService;

	/**
	 * 종목의 기사 목록과 그 시각의 요약을 조회한다. 노출 게이트는 spec §C-5, 상태값 판정은 §C-4다.
	 *
	 * <p><b>어느 상태값이든 200이다</b>(FEED-008) — 개장 전·기사 0건·요약 행 없음·서술 실패가 모두 정상
	 * 응답이고 오류가 아니다. 없는 종목만 404이며 그 판정은 서비스가 한다.
	 *
	 * <p><b>이 경로는 순수 조회다.</b> 요약은 개장 전 배치가 미리 만들어 두므로 여기서 LLM을 부르지도 DB에
	 * 쓰지도 않는다 (FEED-008).
	 */
	@GetMapping("/{instrumentId}/news")
	public ResponseEntity<InstrumentNewsResponse> getInstrumentNews(
		@PathVariable
		Long instrumentId) {
		return ResponseEntity.ok(instrumentNewsQueryService.getInstrumentNews(instrumentId));
	}
}
