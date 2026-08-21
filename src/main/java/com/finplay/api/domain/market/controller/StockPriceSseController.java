// 주식 전용 SSE 구독 엔드포인트(/api/stocks/stream)를 제공하는 컨트롤러 (이슈 #19)
package com.finplay.api.domain.market.controller;

import com.finplay.api.domain.market.service.StockPriceStreamService;
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

	private final StockPriceStreamService stockPriceStreamService;

	// 인증은 SecurityConfig의 anyRequest().authenticated()로 처리된다 — 프론트는 fetch()에 Authorization:
	// Bearer 헤더를 실어 요청하고(브라우저 EventSource는 커스텀 헤더를 못 보내 미사용, plan.md SSE 계약), 토큰이
	// 없거나 유효하지 않으면 JwtAuthenticationFilter·RestAuthenticationEntryPoint가 401을 반환한다.
	// retry 힌트·heartbeat·연결 종료 정리는 SseEmitterRegistry(이슈 #18)가 createEmitter() 시점에 배선한다. 이
	// 컨트롤러는 emitter 생성 → snapshot 전송(sendSnapshot, 프록시를 거치는 public 트랜잭션 메서드) → activate(매분
	// broadcast 대상에 추가) 순서로 직접 호출한다 — activate를 snapshot 전송 뒤로 미루면 그 사이 진행 중인 매분
	// broadcast가 아직 활성화되지 않은 이 emitter를 대상에 포함할 수 없어, 새 구독자가 snapshot보다 price를 먼저
	// 받는 경합이 애초에 생기지 않는다(락 불필요, PR #94 후속 리뷰).
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		SseEmitter emitter = stockPriceStreamService.createEmitter();
		stockPriceStreamService.sendSnapshot(emitter);
		stockPriceStreamService.activate(emitter);
		return emitter;
	}
}
