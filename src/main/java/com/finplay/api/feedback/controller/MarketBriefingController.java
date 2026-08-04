// 시장 단위 개장 전 브리핑 조회 요청을 처리하는 컨트롤러
package com.finplay.api.feedback.controller;

import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.service.MarketBriefingService;
import com.finplay.api.market.domain.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 소유 도메인은 feedback이다 (spec §C-6). URL 접두사가 market이지만 종목 축이 아니라 시장 축의 리소스라
// 경로를 그렇게 두었고, 클래스는 도메인 패키지에 둔다 (PriceMoveController·InstrumentNewsController와 같은 형태).
//
// 인증은 SecurityConfig의 anyRequest().authenticated()가 건다. 공개 GET 목록에 넣지 않으므로 토큰 없이
// 호출하면 401 UNAUTHORIZED다 (docs/api-contracts.md).
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketBriefingController {

	private final MarketBriefingService marketBriefingService;

	/**
	 * 시장의 개장 전 브리핑을 조회한다. 상태값 판정은 spec §C-4, 구간은 §C-2의 {@code 전장}이다.
	 *
	 * <p><b>{@code market}은 필수이고 {@code STOCK}·{@code CRYPTO} 리터럴만 허용한다.</b> 누락이면
	 * {@code MissingServletRequestParameterException}, 그 밖의 값이면 열거형 변환이 실패해
	 * {@code MethodArgumentTypeMismatchException}이 나고 <b>둘 다 {@code GlobalExceptionHandler}가 400
	 * {@code VALIDATION_ERROR}로 매핑한다</b> — 여기서 따로 검증하지 않는다. 스프링의 문자열→열거형 변환은
	 * 대소문자를 구분하므로 {@code stock} 같은 값도 400이다.
	 *
	 * <p><b>시장은 {@code market/domain/Market}이다</b>(§C-8) — {@code account/domain/Market}과 값 이름은
	 * 같지만 이 파라미터는 계좌가 아니라 종목·시장 축이다.
	 *
	 * <p><b>어느 상태값이든 200이다</b>(FEED-009) — 개장 전·재생세션 미준비·기사 0건·브리핑 행 없음·서술
	 * 실패가 모두 정상 응답이고 오류가 아니다. <b>이 경로는 순수 조회다.</b>
	 */
	@GetMapping("/briefing")
	public ResponseEntity<MarketBriefingResponse> getBriefing(
		@RequestParam
		Market market) {
		return ResponseEntity.ok(marketBriefingService.getBriefing(market));
	}
}
