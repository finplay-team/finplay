// Redis 채널(CryptoPriceMoveCardPublisher.CHANNEL) 메시지를 로컬 SseEmitterRegistry(Market.CRYPTO)로 다시 뿌리는 리스너 (ADR-0018 §결정 4)
package com.finplay.api.market.sse;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.PriceMoveCardConfirmedEvent;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code RedisMessageListenerContainer}가 자체 스레드(기본 {@code SimpleAsyncTaskExecutor})에서 이 리스너를 실행한다
 * — {@code CryptoPriceMoveWatcher}의 {@code @Scheduled} 스레드와 완전히 분리돼 있어 느린 구독자 전송이 감시 틱을
 * 지연시키지 않는다(ADR-0018 §결정 5). 역직렬화 실패·개별 emitter 전송 실패 모두 이 리스너 스레드를 죽이지 않게
 * 예외를 여기서 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoCardPushSubscriber implements MessageListener {

	private final SseEmitterRegistry sseEmitterRegistry;

	private final ObjectMapper objectMapper;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		PriceMoveCardConfirmedEvent event;
		try {
			event = objectMapper.readValue(message.getBody(), PriceMoveCardConfirmedEvent.class);
		} catch (RuntimeException ex) {
			// 형식이 깨진 메시지다 — 예외를 삼키고 이 리스너 스레드가 다음 메시지를 계속 처리하게 둔다.
			log.warn("코인 변동 카드 확정 메시지 역직렬화 실패 - 이 메시지는 버린다", ex);
			return;
		}
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.CRYPTO)) {
			send(emitter, event);
		}
	}

	// emitter 하나의 전송 실패가 나머지 구독자의 push를 막지 않게 개별 처리한다
	// (StockPriceStreamService.broadcastPriceEvent와 동일 패턴, 이슈 #18 계약 재사용).
	private void send(SseEmitter emitter, PriceMoveCardConfirmedEvent event) {
		try {
			emitter.send(SseEmitter.event().name("priceMoveCardConfirmed").data(event));
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			emitter.completeWithError(e);
		}
	}
}
