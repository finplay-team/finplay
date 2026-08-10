// snapshot 구성(코인 전체·가격없음 포함)과 CryptoPriceUpdatedEvent 기반 price push, 5초 폴링 status push 규칙(변경분만 전송)을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.MarketPriceEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.market.dto.sse.MarketStatusEvent;
import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.sse.SseEmitterRegistry;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;

class CryptoPriceStreamServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 9, 5, 12);

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PriceStore priceStore = mock(PriceStore.class);
	private final SseEmitterRegistry sseEmitterRegistry = mock(SseEmitterRegistry.class);
	private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
	private final CryptoPriceStreamService service = new CryptoPriceStreamService(
		instrumentRepository, priceQueryService, priceStore, sseEmitterRegistry, clock);

	private static Instrument cryptoInstrument(long id, String symbol) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, symbol, symbol + "코인", BigDecimal.ONE, 5000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private static List<Instrument> twelveCryptoInstruments() {
		return IntStream.rangeClosed(1, 12)
			.mapToObj(i -> cryptoInstrument(i, "SYM" + i))
			.toList();
	}

	private static PriceQuoteDto availableQuote(BigDecimal price, LocalDateTime sourceTime) {
		return new PriceQuoteDto(price, sourceTime, PriceStatus.AVAILABLE, null);
	}

	private static PriceQuoteDto unavailableQuote() {
		return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
	}

	private static String joinSentTextEvents(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(CryptoPriceStreamServiceTest::unwrapData)
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

	// data 페이로드(JSON 직렬화 전의 실제 객체)만 골라 반환한다 — joinSentTextEvents는 event:/id: 같은 텍스트
	// 헤더만 모으고 APPLICATION_JSON으로 감싸진 객체(String이 아님)는 걸러내므로, payload 필드값 검증에는
	// 이 헬퍼로 실제 객체를 꺼내 사용한다.
	private static <T> T lastPayloadOfType(SseEmitterTestHandler handler, Class<T> type) {
		return handler.getSentEvents().stream()
			.map(CryptoPriceStreamServiceTest::unwrapData)
			.filter(type::isInstance)
			.map(type::cast)
			.reduce((first, second) -> second)
			.orElseThrow(() -> new AssertionError("전송된 이벤트 중 " + type.getSimpleName() + " 타입 payload가 없습니다."));
	}

	private void stubInstruments(List<Instrument> instruments) {
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.CRYPTO)).thenReturn(instruments);
	}

	// ---------- buildSnapshot ----------

	@Test
	void buildSnapshotIncludesAllTwelveCryptoInstrumentsIncludingTheOneWithoutPriceAndHasNoMarketStatusOrTradingDate() {
		List<Instrument> instruments = twelveCryptoInstruments();
		stubInstruments(instruments);
		for (int i = 1; i <= 11; i++) {
			when(priceQueryService.getPriceQuote(instruments.get(i - 1)))
				.thenReturn(availableQuote(BigDecimal.valueOf(1000 + i), LocalDateTime.of(2026, 8, 10, 9, i % 5, 0)));
		}
		when(priceQueryService.getPriceQuote(instruments.get(11))).thenReturn(unavailableQuote());

		MarketSnapshotEvent snapshot = service.buildSnapshot();

		assertThat(snapshot.market()).isEqualTo(Market.CRYPTO);
		assertThat(snapshot.sourceTradingDate()).isNull();
		assertThat(snapshot.marketStatus()).isNull();
		assertThat(snapshot.prices()).hasSize(12);
		InstrumentPriceSnapshot last = snapshot.prices().get(11);
		assertThat(last.symbol()).isEqualTo("SYM12");
		assertThat(last.price()).isNull();
		assertThat(last.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		InstrumentPriceSnapshot first = snapshot.prices().get(0);
		assertThat(first.symbol()).isEqualTo("SYM1");
		assertThat(first.status()).isEqualTo(PriceStatus.AVAILABLE);
	}

	// ---------- sendSnapshot ----------

	@Test
	void sendSnapshotSendsEventNamedSnapshotWithoutIdToTheGivenEmitterOnly() throws Exception {
		Instrument instrument = cryptoInstrument(1, "BTC");
		stubInstruments(List.of(instrument));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("140000000"), LocalDateTime.of(2026, 8, 10, 9, 0, 0)));
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		service.sendSnapshot(emitter);

		String sent = joinSentTextEvents(handler);
		assertThat(sent).contains("event:snapshot");
		assertThat(sent).doesNotContain("id:");
	}

	// ---------- onPriceUpdated: CryptoPriceUpdatedEvent → price ----------

	@Test
	void onPriceUpdatedBroadcastsPriceEventWithSecondPrecisionIdToEveryRegisteredEmitter() throws Exception {
		SseEmitter emitterA = new SseEmitter();
		SseEmitter emitterB = new SseEmitter();
		SseEmitterTestHandler handlerA = new SseEmitterTestHandler();
		SseEmitterTestHandler handlerB = new SseEmitterTestHandler();
		handlerA.attachTo(emitterA);
		handlerB.attachTo(emitterB);
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(emitterA, emitterB));
		LocalDateTime receivedAt = LocalDateTime.of(2026, 8, 10, 9, 5, 33);
		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent("BTC", new BigDecimal("140000000"), receivedAt);

		service.onPriceUpdated(event);

		String sentA = joinSentTextEvents(handlerA);
		String sentB = joinSentTextEvents(handlerB);
		assertThat(sentA).contains("event:price");
		assertThat(sentA).contains("id:CRYPTO:BTC:20260810090533");
		assertThat(sentB).contains("event:price");
		MarketPriceEvent payload = lastPayloadOfType(handlerA, MarketPriceEvent.class);
		assertThat(payload.market()).isEqualTo(Market.CRYPTO);
		assertThat(payload.symbol()).isEqualTo("BTC");
		assertThat(payload.price()).isEqualByComparingTo("140000000");
		assertThat(payload.sourceTime()).isEqualTo(receivedAt);
		assertThat(payload.emittedAt()).isEqualTo(NOW);
		assertThat(payload.sourceTradingDate()).isNull();
		assertThat(payload.marketStatus()).isNull();
	}

	@Test
	void onPriceUpdatedSkipsFailingEmitterButStillReachesHealthyOne() throws Exception {
		SseEmitter failingEmitter = new SseEmitter();
		SseEmitter healthyEmitter = new SseEmitter();
		SseEmitterTestHandler failingHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler healthyHandler = new SseEmitterTestHandler();
		failingHandler.attachTo(failingEmitter);
		healthyHandler.attachTo(healthyEmitter);
		failingHandler.failOnNextSend();
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(failingEmitter, healthyEmitter));
		LocalDateTime receivedAt = LocalDateTime.of(2026, 8, 10, 9, 5, 33);
		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent("ETH", new BigDecimal("5000000"), receivedAt);

		service.onPriceUpdated(event);

		assertThat(failingHandler.isCompleteWithErrorCalled()).isTrue();
		assertThat(joinSentTextEvents(healthyHandler)).contains("event:price");
	}

	// ---------- publishConnectionStatusIfChanged: status ----------

	@Test
	void publishConnectionStatusIfChangedSendsStatusEventOnceWhenChangedAndSuppressesWhenUnchanged()
		throws Exception {
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.CRYPTO)).thenReturn(List.of(emitter));

		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		service.publishConnectionStatusIfChanged();
		String sentAfterChange = joinSentTextEvents(handler);

		assertThat(sentAfterChange).contains("event:status");
		assertThat(sentAfterChange).doesNotContain("id:");
		MarketStatusEvent payload = lastPayloadOfType(handler, MarketStatusEvent.class);
		assertThat(payload.market()).isEqualTo(Market.CRYPTO);
		assertThat(payload.symbol()).isNull();
		assertThat(payload.marketStatus()).isNull();
		assertThat(payload.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(payload.reason()).isEqualTo("DISCONNECTED");
		assertThat(payload.emittedAt()).isEqualTo(NOW);

		int eventsAfterFirstChange = handler.getSentEvents().size();
		service.publishConnectionStatusIfChanged();

		assertThat(handler.getSentEvents()).hasSize(eventsAfterFirstChange);
	}

	@Test
	void publishConnectionStatusIfChangedDoesNotFireFalseEventOnFirstRunRightAfterStartupBaseline() {
		// @PostConstruct 기준선이 없다면 첫 스케줄 실행에서 기동 시점 상태를 "변경"으로 오판한다.
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);

		service.initializeBaseline();
		service.publishConnectionStatusIfChanged();

		verify(sseEmitterRegistry, never()).getEmitters(any());
	}

	// ---------- createEmitter/activate ----------

	@Test
	void createEmitterDelegatesToRegistryCreateEmitterForCryptoMarket() {
		SseEmitter emitter = new SseEmitter();
		when(sseEmitterRegistry.createEmitter(Market.CRYPTO)).thenReturn(emitter);

		SseEmitter result = service.createEmitter();

		assertThat(result).isSameAs(emitter);
	}

	@Test
	void activateDelegatesToRegistryActivateForCryptoMarket() {
		SseEmitter emitter = new SseEmitter();

		service.activate(emitter);

		verify(sseEmitterRegistry).activate(Market.CRYPTO, emitter);
	}
}
