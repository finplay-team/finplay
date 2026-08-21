// SseEmitterRegistry의 market별 emitter 등록·제거와 onCompletion/onTimeout/onError/heartbeat 정리 동작을 검증하는 단위 테스트다.
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

	// ---------- createEmitter/activate 분리로 새로 생긴 엣지 케이스 (PR #94 재설계 검토) ----------

	// 재현: 컨트롤러는 createEmitter() → sendSnapshot(emitter) → activate(emitter) 순서로 호출한다.
	// StockPriceStreamService.sendSnapshot()이 내부에서 emitter.send(...)에 실패하면(클라이언트가 snapshot을 받기도
	// 전에 이미 연결을 끊은 경우) private send() 헬퍼가 예외를 삼키고 emitter.completeWithError(e)만 호출한 뒤 정상
	// 반환한다 — 컨트롤러는 실패 여부를 알 방법이 없어 그대로 activate(emitter)를 호출한다.
	//
	// 이 테스트는 completeWithError() 호출만으로는 SseEmitterRegistry.createEmitter()가 배선한 onError 콜백이
	// 즉시 트리거되지 않음을 보여준다 — DeferredResult.setErrorResult()는 실제 서블릿 컨테이너의 비동기 디스패치가
	// 있어야 onError/onCompletion 콜백까지 이어진다(SseEmitterRegistry.sendHeartbeat()의 "complete()는 플래그만
	// 즉시 세팅하고 컨테이너 콜백은 나중에 온다"는 주석과 동일한 레이스). 따라서 activate()는 이미 죽은 emitter를
	// 그대로 활성 broadcast 집합에 밀어넣는다: getEmitters()가 즉시 이 emitter를 포함하게 되어, sendHeartbeat()가
	// 다음 tick(최대 20초 후)에 explicit remove로 청소하기 전까지는 매분 price/status broadcast가 이 죽은 emitter에도
	// 매번 실패하는 send를 시도한다. 영구 누수는 아니지만(heartbeat가 자체적으로 emitters.remove()를 명시 호출해
	// 결국 청소한다), sendSnapshot()의 send 실패를 activate() 이전에 컨트롤러가 전혀 알지 못한다는 점은 재설계가
	// 만든 새로운 엣지 케이스다.
	@Test
	void activatingAfterSendSnapshotFailsInsertsAnAlreadyDeadEmitterIntoTheBroadcastSet() throws IOException {
		SseEmitterRegistry registry = new SseEmitterRegistry();
		SseEmitter emitter = registry.createEmitter(Market.STOCK);
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		// register()로 만들어진 retry 힌트 전송은 attachTo 시점에 이미 flush됐으므로, 여기서부터 실패를 강제한다.
		handler.failOnNextSend();

		// StockPriceStreamService.sendSnapshot() 내부의 private send() 헬퍼와 동일한 처리: 실패하면 completeWithError만
		// 호출하고 예외를 밖으로 던지지 않는다 — 컨트롤러 입장에서는 성공한 것과 구분이 안 된다.
		try {
			emitter.send(SseEmitter.event().name("snapshot").data("payload"));
		} catch (IOException | RuntimeException e) {
			emitter.completeWithError(e);
		}

		// completeWithError()만으로는 onError 콜백(=registry의 emitters.remove())이 아직 트리거되지 않았으므로
		// activate() 이전에도 이미 활성 집합에는 없다(createEmitter만으로는 애초에 집합에 없었다) — 그리고 activate()가
		// 무조건 호출되면서 이 죽은 emitter를 활성 집합에 그대로 편입시킨다.
		registry.activate(Market.STOCK, emitter);

		assertThat(registry.getEmitters(Market.STOCK))
			.as("sendSnapshot 실패를 컨트롤러가 모른 채 activate()를 호출하면 이미 죽은 emitter가 broadcast 대상에 편입된다")
			.contains(emitter);
		assertThat(handler.isCompleteWithErrorCalled()).isTrue();

		// 다음 heartbeat tick이 되어서야(명시적 emitters.remove() 경로) 비로소 청소된다 — 그 전까지는 매분
		// price/status broadcast도 이 emitter에 실패하는 send를 매번 시도하게 된다.
		registry.sendHeartbeat();

		assertThat(registry.getEmitters(Market.STOCK)).doesNotContain(emitter);
	}
}
