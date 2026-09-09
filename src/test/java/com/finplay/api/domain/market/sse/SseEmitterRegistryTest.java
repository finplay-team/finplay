package com.finplay.api.domain.market.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
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

	@Test
	void activatingAfterSendSnapshotFailsInsertsAnAlreadyDeadEmitterIntoTheBroadcastSet() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.createEmitter(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		handler.failOnNextSend();

		try {
			emitter.send(SseEmitter.event().name("snapshot").data("payload"));
		} catch (IOException | RuntimeException e) {
			emitter.completeWithError(e);
		}

		registry.activate(Market.STOCK, emitter);

		assertThat(registry.getEmitters(Market.STOCK))
			.as("sendSnapshot 실패를 컨트롤러가 모른 채 activate()를 호출하면 이미 죽은 emitter가 broadcast 대상에 편입된다")
			.contains(emitter);
		assertThat(handler.isCompleteWithErrorCalled()).isTrue();

		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
	}
}
