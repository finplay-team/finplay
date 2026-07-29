// snapshot 구성(16종 전체·가격없음 포함)과 매분 price·status 이벤트 push 규칙(id는 price에만, 변경분만 전송)을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.sse.SseEmitterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;

class StockPriceStreamServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 9, 5, 0);
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 29);

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
	private final SseEmitterRegistry sseEmitterRegistry = mock(SseEmitterRegistry.class);
	private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
	private final StockPriceStreamService service = new StockPriceStreamService(
		instrumentRepository, priceQueryService, stockPriceProvider, sseEmitterRegistry, clock);

	private static Instrument stockInstrument(long id, String symbol) {
		Instrument instrument = Instrument.create(
			Market.STOCK, symbol, symbol + "종목", BigDecimal.ONE, 70000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	// 16개 중 마지막 하나만 가격 없음(UNAVAILABLE)으로 구성한다.
	private static List<Instrument> sixteenStockInstruments() {
		return IntStream.rangeClosed(1, 16)
			.mapToObj(i -> stockInstrument(i, "SYM" + i))
			.toList();
	}

	private static PriceQuoteDto availableQuote(BigDecimal price, LocalDateTime sourceTime) {
		return new PriceQuoteDto(price, sourceTime, PriceStatus.AVAILABLE, SOURCE_TRADING_DATE);
	}

	private static PriceQuoteDto unavailableQuote() {
		return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, SOURCE_TRADING_DATE);
	}

	// SseEventBuilder.build()이 만드는 Set은 원소를 그대로 String으로 담지 않고, id·event 등 헤더 텍스트를
	// ResponseBodyEmitter.DataWithMediaType(String, TEXT_PLAIN)으로 감싸 보관한다(실제 JSON data 페이로드는
	// DataWithMediaType(Object, APPLICATION_JSON)) — 두 경우 모두 언랩한 뒤 문자열인 것만 이어붙인다.
	private static String joinSentTextEvents(SseEmitterTestHandler handler) {
		return handler.getSentEvents().stream()
			.map(StockPriceStreamServiceTest::unwrapData)
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

	private void stubInstruments(List<Instrument> instruments) {
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK)).thenReturn(instruments);
	}

	// ---------- buildSnapshot ----------

	@Test
	void buildSnapshotIncludesAllSixteenStockInstrumentsIncludingTheOneWithoutPrice() {
		List<Instrument> instruments = sixteenStockInstruments();
		stubInstruments(instruments);
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		for (int i = 1; i <= 15; i++) {
			when(priceQueryService.getPriceQuote((long)i))
				.thenReturn(availableQuote(BigDecimal.valueOf(70000 + i), LocalDateTime.of(SOURCE_TRADING_DATE,
					LocalTime.of(9, i % 5))));
		}
		when(priceQueryService.getPriceQuote(16L)).thenReturn(unavailableQuote());

		MarketSnapshotEvent snapshot = service.buildSnapshot();

		assertThat(snapshot.market()).isEqualTo(Market.STOCK);
		assertThat(snapshot.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(snapshot.prices()).hasSize(16);
		InstrumentPriceSnapshot last = snapshot.prices().get(15);
		assertThat(last.symbol()).isEqualTo("SYM16");
		assertThat(last.price()).isNull();
		assertThat(last.sourceTime()).isNull();
		assertThat(last.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		InstrumentPriceSnapshot first = snapshot.prices().get(0);
		assertThat(first.symbol()).isEqualTo("SYM1");
		assertThat(first.status()).isEqualTo(PriceStatus.AVAILABLE);
	}

	@Test
	void buildSnapshotKeepsLastValidPriceEvenWhenMarketStatusIsClosed() {
		// 장 마감 후에도 마지막 유효가격이 UNAVAILABLE로 뒤바뀌지 않아야 한다 — marketStatus는 종목별 가격과 독립적이어야 한다.
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		LocalDateTime lastCloseTime = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(15, 30));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), lastCloseTime));

		MarketSnapshotEvent snapshot = service.buildSnapshot();

		assertThat(snapshot.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		InstrumentPriceSnapshot only = snapshot.prices().get(0);
		assertThat(only.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(only.price()).isEqualByComparingTo("71000");
		assertThat(only.sourceTime()).isEqualTo(lastCloseTime);
	}

	// ---------- sendSnapshot ----------

	@Test
	void sendSnapshotSendsEventNamedSnapshotWithoutIdToTheGivenEmitterOnly() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(1L))
			.thenReturn(availableQuote(new BigDecimal("71000"), LocalDateTime.of(SOURCE_TRADING_DATE,
				LocalTime.of(9, 0))));
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);

		service.sendSnapshot(emitter);

		String sent = joinSentTextEvents(handler);
		assertThat(sent).contains("event:snapshot");
		assertThat(sent).doesNotContain("id:");
	}

	// ---------- publishScheduledUpdates: price 이벤트 ----------

	@Test
	void publishScheduledUpdatesSendsPriceEventWithIdOnlyForNewlyRevealedPrice() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		// 기동 시점 기준선 — 아직 가격 없음.
		when(priceQueryService.getPriceQuote(1L)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitter));
		// 이번 분에 새로 가격이 공개됨.
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

		service.publishScheduledUpdates();

		String sent = joinSentTextEvents(handler);
		assertThat(sent).contains("event:price");
		assertThat(sent).contains("id:STOCK:SYM1:202607290905");
	}

	@Test
	void publishScheduledUpdatesDoesNotResendPriceEventWhenSourceTimeUnchanged() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		LocalDateTime sourceTime = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 0));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), sourceTime));
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitter));

		// 같은 sourceTime의 가격이 반복 조회되어도(신규 공개 아님) 이벤트를 다시 보내지 않는다.
		service.publishScheduledUpdates();

		assertThat(handler.getSentEvents()).isEmpty();
		verify(sseEmitterRegistry, never()).getEmitters(any());
	}

	@Test
	void publishScheduledUpdatesDoesNotFireFalseEventOnFirstRunRightAfterStartupBaseline() throws Exception {
		// @PostConstruct 기준선이 없다면(초기 lastKnownQuotes 비어있음) 첫 스케줄 실행에서 이미 있던 가격도 "신규 공개"로 오판한다.
		// initializeBaseline()이 이를 막는지 확인한다.
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		LocalDateTime sourceTime = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(15, 30));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), sourceTime));

		service.initializeBaseline();
		service.publishScheduledUpdates();

		verify(sseEmitterRegistry, never()).getEmitters(any());
	}

	// ---------- publishScheduledUpdates: status 이벤트 ----------

	@Test
	void publishScheduledUpdatesSendsStatusEventOnceWhenMarketStatusChangesAndSuppressesWhenUnchanged()
		throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(unavailableQuote());
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitter));

		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		service.publishScheduledUpdates();
		String sentAfterFirstChange = joinSentTextEvents(handler);

		assertThat(sentAfterFirstChange).contains("event:status");
		assertThat(sentAfterFirstChange).doesNotContain("id:");

		int eventsAfterFirstChange = handler.getSentEvents().size();
		// marketStatus가 그대로 CLOSED이면 두 번째 호출에서는 추가로 전송하지 않는다.
		service.publishScheduledUpdates();

		assertThat(handler.getSentEvents()).hasSize(eventsAfterFirstChange);
	}

	// ---------- publishScheduledUpdates: 다중 emitter ----------

	@Test
	void publishScheduledUpdatesBroadcastsPriceEventToEveryRegisteredEmitterForStock() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(1L)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		SseEmitter emitterA = new SseEmitter();
		SseEmitter emitterB = new SseEmitter();
		SseEmitterTestHandler handlerA = new SseEmitterTestHandler();
		SseEmitterTestHandler handlerB = new SseEmitterTestHandler();
		handlerA.attachTo(emitterA);
		handlerB.attachTo(emitterB);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitterA, emitterB));
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

		service.publishScheduledUpdates();

		assertThat(joinSentTextEvents(handlerA)).contains("event:price");
		assertThat(joinSentTextEvents(handlerB)).contains("event:price");
	}

	@Test
	void publishScheduledUpdatesSkipsFailingEmitterButStillReachesHealthyOne() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(1L)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		SseEmitter failingEmitter = new SseEmitter();
		SseEmitter healthyEmitter = new SseEmitter();
		SseEmitterTestHandler failingHandler = new SseEmitterTestHandler();
		SseEmitterTestHandler healthyHandler = new SseEmitterTestHandler();
		failingHandler.attachTo(failingEmitter);
		healthyHandler.attachTo(healthyEmitter);
		failingHandler.failOnNextSend();
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(failingEmitter, healthyEmitter));
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(1L)).thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

		service.publishScheduledUpdates();

		assertThat(failingHandler.isCompleteWithErrorCalled()).isTrue();
		assertThat(joinSentTextEvents(healthyHandler)).contains("event:price");
	}
}
