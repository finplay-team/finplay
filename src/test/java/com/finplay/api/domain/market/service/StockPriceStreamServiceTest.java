package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
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
	private final TransactionTemplate transactionTemplate = stubTransactionTemplate();
	private final StockPriceStreamService service = new StockPriceStreamService(
		instrumentRepository, priceQueryService, stockPriceProvider, sseEmitterRegistry, clock, transactionTemplate);

	private static TransactionTemplate stubTransactionTemplate() {
		TransactionTemplate template = mock(TransactionTemplate.class);
		when(template.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(null);
		});
		return template;
	}

	private static Instrument stockInstrument(long id, String symbol) {
		Instrument instrument = Instrument.create(
			Market.STOCK, symbol, symbol + "종목", BigDecimal.ONE, 70000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

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


	@Test
	void buildSnapshotIncludesAllSixteenStockInstrumentsIncludingTheOneWithoutPrice() {
		List<Instrument> instruments = sixteenStockInstruments();
		stubInstruments(instruments);
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		for (int i = 1; i <= 15; i++) {
			when(priceQueryService.getPriceQuote(instruments.get(i - 1)))
				.thenReturn(availableQuote(BigDecimal.valueOf(70000 + i), LocalDateTime.of(SOURCE_TRADING_DATE,
					LocalTime.of(9, i % 5))));
		}
		when(priceQueryService.getPriceQuote(instruments.get(15))).thenReturn(unavailableQuote());

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
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		LocalDateTime lastCloseTime = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(15, 30));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), lastCloseTime));

		MarketSnapshotEvent snapshot = service.buildSnapshot();

		assertThat(snapshot.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		InstrumentPriceSnapshot only = snapshot.prices().get(0);
		assertThat(only.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(only.price()).isEqualByComparingTo("71000");
		assertThat(only.sourceTime()).isEqualTo(lastCloseTime);
	}


	@Test
	void sendSnapshotSendsEventNamedSnapshotWithoutIdToTheGivenEmitterOnly() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(instrument))
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


	@Test
	void publishScheduledUpdatesSendsPriceEventWithIdOnlyForNewlyRevealedPrice() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitter));
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

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
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), sourceTime));
		service.initializeBaseline();
		SseEmitter emitter = new SseEmitter();
		SseEmitterTestHandler handler = new SseEmitterTestHandler();
		handler.attachTo(emitter);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitter));

		service.publishScheduledUpdates();

		assertThat(handler.getSentEvents()).isEmpty();
		verify(sseEmitterRegistry, never()).getEmitters(any());
	}

	@Test
	void publishScheduledUpdatesDoesNotFireFalseEventOnFirstRunRightAfterStartupBaseline() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		LocalDateTime sourceTime = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(15, 30));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), sourceTime));

		service.initializeBaseline();
		service.publishScheduledUpdates();

		verify(sseEmitterRegistry, never()).getEmitters(any());
	}


	@Test
	void publishScheduledUpdatesSendsStatusEventOnceWhenMarketStatusChangesAndSuppressesWhenUnchanged()
		throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(unavailableQuote());
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
		service.publishScheduledUpdates();

		assertThat(handler.getSentEvents()).hasSize(eventsAfterFirstChange);
	}


	@Test
	void publishScheduledUpdatesBroadcastsPriceEventToEveryRegisteredEmitterForStock() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		SseEmitter emitterA = new SseEmitter();
		SseEmitter emitterB = new SseEmitter();
		SseEmitterTestHandler handlerA = new SseEmitterTestHandler();
		SseEmitterTestHandler handlerB = new SseEmitterTestHandler();
		handlerA.attachTo(emitterA);
		handlerB.attachTo(emitterB);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenReturn(List.of(emitterA, emitterB));
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

		service.publishScheduledUpdates();

		assertThat(joinSentTextEvents(handlerA)).contains("event:price");
		assertThat(joinSentTextEvents(handlerB)).contains("event:price");
	}

	@Test
	void publishScheduledUpdatesSkipsFailingEmitterButStillReachesHealthyOne() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(unavailableQuote());
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
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(availableQuote(new BigDecimal("71000"), revealedAt));

		service.publishScheduledUpdates();

		assertThat(failingHandler.isCompleteWithErrorCalled()).isTrue();
		assertThat(joinSentTextEvents(healthyHandler)).contains("event:price");
	}


	@Test
	void createEmitterDelegatesToRegistryCreateEmitterForStockMarket() {
		SseEmitter emitter = new SseEmitter();
		when(sseEmitterRegistry.createEmitter(Market.STOCK)).thenReturn(emitter);

		SseEmitter result = service.createEmitter();

		assertThat(result).isSameAs(emitter);
	}

	@Test
	void activateDelegatesToRegistryActivateForStockMarket() {
		SseEmitter emitter = new SseEmitter();

		service.activate(emitter);

		verify(sseEmitterRegistry).activate(Market.STOCK, emitter);
	}

	@Test
	void createEmitterDoesNotWaitForInFlightScheduledBroadcastToFinish() throws Exception {
		Instrument instrument = stockInstrument(1, "SYM1");
		stubInstruments(List.of(instrument));
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(unavailableQuote());
		service.initializeBaseline();
		LocalDateTime revealedAt = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 5));
		when(priceQueryService.getPriceQuote(instrument)).thenReturn(availableQuote(new BigDecimal("71000"),
			revealedAt));

		SseEmitter existingEmitter = new SseEmitter();
		SseEmitterTestHandler existingHandler = new SseEmitterTestHandler();
		existingHandler.attachTo(existingEmitter);

		List<String> events = new CopyOnWriteArrayList<>();
		CountDownLatch broadcastEnteredLock = new CountDownLatch(1);
		CountDownLatch releaseBroadcast = new CountDownLatch(1);
		when(sseEmitterRegistry.getEmitters(Market.STOCK)).thenAnswer(invocation -> {
			events.add("broadcastStart");
			broadcastEnteredLock.countDown();
			assertThat(releaseBroadcast.await(2, TimeUnit.SECONDS)).isTrue();
			events.add("broadcastEnd");
			return List.of(existingEmitter);
		});

		SseEmitter newEmitter = new SseEmitter();
		when(sseEmitterRegistry.createEmitter(Market.STOCK)).thenAnswer(invocation -> {
			events.add("createEmitterCalled");
			return newEmitter;
		});

		Thread publisher = new Thread(service::publishScheduledUpdates, "publisher");
		publisher.start();
		assertThat(broadcastEnteredLock.await(2, TimeUnit.SECONDS)).isTrue();

		Thread subscriber = new Thread(service::createEmitter, "subscriber");
		subscriber.start();
		subscriber.join(2000);

		assertThat(events).contains("createEmitterCalled");
		assertThat(events).doesNotContain("broadcastEnd");

		releaseBroadcast.countDown();
		publisher.join(2000);

		assertThat(events).containsExactly("broadcastStart", "createEmitterCalled", "broadcastEnd");
	}
}
