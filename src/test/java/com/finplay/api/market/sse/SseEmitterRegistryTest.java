// SseEmitterRegistry의 market별 emitter 등록·제거와 onCompletion/onTimeout/onError/heartbeat 정리 동작을 검증하는 단위 테스트다.
package com.finplay.api.market.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Market;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;

class SseEmitterRegistryTest {

	@Test
	void registerAddsEmitterOnlyToRequestedMarket() {
		SseEmitterRegistry registry = new SseEmitterRegistry();

		SseEmitter stockEmitter = registry.register(Market.STOCK);

		assertThat(registry.getEmitters(Market.STOCK)).containsExactly(stockEmitter);
		assertThat(registry.getEmitters(Market.CRYPTO)).isEmpty();
	}

	@Test
	void registerKeepsStockAndCryptoEmitterListsIndependent() {
		SseEmitterRegistry registry = new SseEmitterRegistry();

		SseEmitter stockEmitter = registry.register(Market.STOCK);
		SseEmitter cryptoEmitter = registry.register(Market.CRYPTO);

		assertThat(registry.getEmitters(Market.STOCK)).containsExactly(stockEmitter);
		assertThat(registry.getEmitters(Market.CRYPTO)).containsExactly(cryptoEmitter);
	}

	@Test
	void registerFlushesRetryHintThroughHandlerOnceInitialized() throws IOException {
		// register() 시점엔 아직 실제 요청 핸들러가 없어 retry 힌트가 emitter 내부에 큐잉된다.
		// 프레임워크가 실제 요청 처리 시 하는 것과 동일하게 handler를 초기화하면 큐잉된 전송이 flush된다.
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();

		handler.attachTo(emitter);

		assertThat(handler.getSentEvents()).isNotEmpty();
	}

	@Test
	void onCompletionCallbackRemovesEmitterFromRegistry() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		handler.triggerCompletion();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
	}

	@Test
	void onTimeoutCallbackRemovesEmitterFromRegistry() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.CRYPTO);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		handler.triggerTimeout();

		assertThat(registry.getEmitters(Market.CRYPTO)).doesNotContain(emitter);
	}

	@Test
	void onErrorCallbackRemovesEmitterFromRegistry() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		handler.triggerError(new IllegalStateException("client disconnected"));

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
	}

	@Test
	void onCompletionCallbackDoesNotAffectEmittersInOtherMarket() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter stockEmitter = registry.register(Market.STOCK);
		SseEmitter cryptoEmitter = registry.register(Market.CRYPTO);
		SseEmitterTestHandler stockHandler = new SseEmitterTestHandler();
		stockHandler.attachTo(stockEmitter);

		stockHandler.triggerCompletion();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(stockEmitter);
		assertThat(registry.getEmitters(Market.CRYPTO)).containsExactly(cryptoEmitter);
	}

	@Test
	void sendHeartbeatSendsCommentToEveryRegisteredEmitterAcrossMarketsWithoutRemovingThem() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter stockEmitter = registry.register(Market.STOCK);
		SseEmitter cryptoEmitter = registry.register(Market.CRYPTO);
		SseEmitterTestHandler stockHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler cryptoHandler = new SseEmitterTestHandler();
		stockHandler.attachTo(stockEmitter);
		cryptoHandler.attachTo(cryptoEmitter);
		int stockEventsBeforeHeartbeat = stockHandler.getSentEvents().size();
		int cryptoEventsBeforeHeartbeat = cryptoHandler.getSentEvents().size();

		registry.sendHeartbeat();

		assertThat(stockHandler.getSentEvents().size()).isGreaterThan(stockEventsBeforeHeartbeat);
		assertThat(cryptoHandler.getSentEvents().size()).isGreaterThan(cryptoEventsBeforeHeartbeat);
		assertThat(registry.getEmitters(Market.STOCK)).containsExactly(stockEmitter);
		assertThat(registry.getEmitters(Market.CRYPTO)).containsExactly(cryptoEmitter);
	}

	@Test
	void sendHeartbeatRemovesAndCompletesEmitterWithErrorWhenSendFailsWithIOException() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		handler.failOnNextSend();

		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
		assertThat(handler.isCompleteWithErrorCalled()).isTrue();
	}

	@Test
	void sendHeartbeatAfterEmitterCompletesRemovesItWithoutThrowing() throws IOException {
		// emitter.complete()는 내부 complete 플래그만 즉시 세팅하고, 컨테이너의 onCompletion 콜백(레지스트리에서
		// emitters.remove()를 실행하는 경로)은 별도로 트리거되기 전까지는 호출되지 않는다. 그 사이 tick이 오면
		// 레지스트리는 이미 complete()된 emitter에 send()를 시도하게 되고, 이는 IOException이 아니라 검사되지
		// 않는 IllegalStateException을 던진다. sendHeartbeat()가 이를 잡아 목록에서 정리하는지 검증한다.
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.register(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		emitter.complete();

		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
		assertThat(handler.isCompleteWithErrorCalled()).isTrue();
	}

	@Test
	void sendHeartbeatSkipsFailedEmitterButStillReachesTheNextOne() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter failingEmitter = registry.register(Market.STOCK);
		SseEmitter healthyEmitter = registry.register(Market.STOCK);
		SseEmitterTestHandler failingHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler healthyHandler = new SseEmitterTestHandler();
		failingHandler.attachTo(failingEmitter);
		healthyHandler.attachTo(healthyEmitter);
		int healthyEventsBeforeHeartbeat = healthyHandler.getSentEvents().size();

		failingEmitter.complete();

		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(failingEmitter).containsExactly(healthyEmitter);
		assertThat(healthyHandler.getSentEvents().size()).isGreaterThan(healthyEventsBeforeHeartbeat);
	}

	@Test
	void sendHeartbeatWithNoRegisteredEmittersDoesNothing() {
		SseEmitterRegistry registry = new SseEmitterRegistry();

		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).isEmpty();
		assertThat(registry.getEmitters(Market.CRYPTO)).isEmpty();
	}
}
