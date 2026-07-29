// 주식 전용 SSE 구독 엔드포인트(/api/stocks/stream)를 제공하는 컨트롤러 (이슈 #19)
package com.finplay.api.market.controller;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.StockPriceStreamService;
import com.finplay.api.market.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockPriceSseController {

	private final SseEmitterRegistry sseEmitterRegistry;
	private final StockPriceStreamService stockPriceStreamService;

	// 인증은 SecurityConfig의 anyRequest().authenticated()로 처리된다 — 프론트는 fetch()에 Authorization:
	// Bearer 헤더를 실어 요청하고(브라우저 EventSource는 커스텀 헤더를 못 보내 미사용, plan.md SSE 계약), 토큰이
	// 없거나 유효하지 않으면 JwtAuthenticationFilter·RestAuthenticationEntryPoint가 401을 반환한다.
	// retry 힌트·heartbeat·연결 종료 정리는 SseEmitterRegistry(이슈 #18)가 register() 시점에 배선한다.
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		SseEmitter emitter = sseEmitterRegistry.register(Market.STOCK);
		stockPriceStreamService.sendSnapshot(emitter);
		return emitter;
	}
}
