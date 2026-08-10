// 코인 전용 SSE 구독 엔드포인트(/api/cryptos/stream)를 제공하는 컨트롤러 (ADR-0018, tasks.md 항목 1)
package com.finplay.api.market.controller;

import com.finplay.api.market.service.CryptoPriceStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/cryptos")
@RequiredArgsConstructor
public class CryptoPriceSseController {

	private final CryptoPriceStreamService cryptoPriceStreamService;

	// 인증은 SecurityConfig의 anyRequest().authenticated()로 처리된다 — StockPriceSseController와 동일하게
	// 프론트는 fetch()에 Authorization: Bearer 헤더를 실어 요청하고(브라우저 EventSource는 커스텀 헤더를 못 보내
	// 미사용), 토큰이 없거나 유효하지 않으면 401을 반환한다. retry 힌트·heartbeat·연결 종료 정리는
	// SseEmitterRegistry(이슈 #18)가 createEmitter() 시점에 배선한다. emitter 생성 → snapshot 전송(sendSnapshot,
	// 프록시를 거치는 public 트랜잭션 메서드) → activate(브로드캐스트 대상에 추가) 순서를 StockPriceSseController와
	// 동일하게 지켜야 새 구독자가 snapshot보다 price를 먼저 받는 경합이 생기지 않는다(락 불필요, PR #94 후속 리뷰).
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		SseEmitter emitter = cryptoPriceStreamService.createEmitter();
		cryptoPriceStreamService.sendSnapshot(emitter);
		cryptoPriceStreamService.activate(emitter);
		return emitter;
	}
}
