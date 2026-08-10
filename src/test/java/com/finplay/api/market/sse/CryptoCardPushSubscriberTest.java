// CryptoCardPushSubscriber의 정상 수신 브로드캐스트, 역직렬화 실패 흡수(리스너 생존), emitter별 전송 실패 격리를 검증하는 단위 테스트다.
package com.finplay.api.market.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.PriceMoveCardConfirmedEvent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;
import tools.jackson.databind.ObjectMapper;

class CryptoCardPushSubscriberTest {

	private static final LocalDateTime EMITTED_AT = LocalDateTime.of(2026, 8, 10, 15, 30, 0);

	private final SseEmitterRegistry sseEmitterRegistry = mock(SseEmitterRegistry.class);

	// StockPriceStreamServiceTest와 동일 관례 — Boot가 주는 것과 같은 종류(Jackson 3)의 진짜 매퍼를 쓴다.
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final CryptoCardPushSubscriber subscriber = new CryptoCardPushSubscriber(
		sseEmitterRegistry, objectMapper);

	private static Message messageWithBody(byte[] body) {
		Message message = mock(Message.class);
		when(message.getBody()).thenReturn(body);
		return message;
	}

	private static Message validMessage(Long instrumentId, Long priceMoveEventId) {
		PriceMoveCardConfirmedEvent event = new PriceMoveCardConfirmedEvent(
			Market.CRYPTO, instrumentId, priceMoveEventId, EMITTED_AT);
		ObjectMapper mapper = new ObjectMapper();
		return messageWithBody(mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
	}

	// SseEventBuilder.build()가 만드는 Set 원소를 문자열로 이어붙인다 (StockPriceStreamServiceTest와 동일 헬퍼).
	private static String joinSentTextEvents(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(CryptoCardPushSubscriberTest::unwrapData)
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.collect(Collectors.joining());
	}

	private static Object unwrapData(Object sentEvent) {
		if (sentEvent instanceof ResponseBodyEmitter.DataWithMediaType dataWithMediaType) {
			return dataWithMediaType.getData();
		}
		return sentEvent;
	}

	// SseEventBuilder.build()는 실제 JSON 문자열이 아니라 데이터 객체 그 자체를 DataWithMediaType(Object,
	// APPLICATION_JSON)로 큐잉한다 — 실제 직렬화는 프레임워크의 HttpMessageConverter가 하며 SseEmitterTestHandler는
	// 이를 재현하지 않는다(MarketPriceEventTest가 이미 @JsonTest로 직렬화 계약을 커버). 여기서는 전송된 페이로드
	// 객체 자체를 꺼내 필드값을 검증한다.
	private static PriceMoveCardConfirmedEvent sentEventPayload(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(CryptoCardPushSubscriberTest::unwrapData)
			.filter(PriceMoveCardConfirmedEvent.class::isInstance)
			.map(PriceMoveCardConfirmedEvent.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("priceMoveCardConfirmed 데이터 페이로드가 전송되지 않았다"));
	}

	@Test
	void onMessageBroadcastsPriceMoveCardConfirmedEventToEveryCryptoEmitter() throws Exception {
		SseEmitter emitterA = new SseEmitter();
		SseEmitter emitterB = new SseEmitter();
		SseEmitterTestHandler handlerA = new SseEmitterTestHandler();
		SseEmitterTestHandler handlerB = new SseEmitterTestHandler();
		handlerA.attachTo(emitterA);
		handlerB.attachTo(emitterB);
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(emitterA, emitterB));

		subscriber.onMessage(validMessage(5L, 100L), null);

		assertThat(joinSentTextEvents(handlerA)).contains("event:priceMoveCardConfirmed");
		PriceMoveCardConfirmedEvent payloadA = sentEventPayload(handlerA);
		assertThat(payloadA.market()).isEqualTo(Market.CRYPTO);
		assertThat(payloadA.instrumentId()).isEqualTo(5L);
		assertThat(payloadA.priceMoveEventId()).isEqualTo(100L);
		assertThat(joinSentTextEvents(handlerB)).contains("event:priceMoveCardConfirmed");
		assertThat(sentEventPayload(handlerB).instrumentId()).isEqualTo(5L);
	}

	@Test
	void onMessageSwallowsMalformedPayloadAndKeepsListenerAliveForNextMessage() throws Exception {
		Message malformed = messageWithBody("{not valid json".getBytes(StandardCharsets.UTF_8));
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(emitter));

		// 형식이 깨진 메시지가 예외를 던지지 않아야 한다 — 리스너 스레드가 죽지 않는다는 것을 보여준다.
		subscriber.onMessage(malformed, null);
		assertThat(handler.getSentEvents()).isEmpty();

		// 같은 리스너 인스턴스가 다음 정상 메시지를 문제없이 처리한다.
		subscriber.onMessage(validMessage(7L, 200L), null);
		assertThat(joinSentTextEvents(handler)).contains("event:priceMoveCardConfirmed");
	}

	@Test
	void onMessageIsolatesFailingEmitterButStillReachesHealthyOne() throws Exception {
		SseEmitter failingEmitter = new SseEmitter();
		SseEmitter healthyEmitter = new SseEmitter();
		SseEmitterTestHandler failingHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler healthyHandler = new SseEmitterTestHandler();
		failingHandler.attachTo(failingEmitter);
		healthyHandler.attachTo(healthyEmitter);
		failingHandler.failOnNextSend();
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(failingEmitter, healthyEmitter));

		subscriber.onMessage(validMessage(5L, 100L), null);

		assertThat(failingHandler.isCompleteWithErrorCalled()).isTrue();
		assertThat(joinSentTextEvents(healthyHandler)).contains("event:priceMoveCardConfirmed");
	}
}
