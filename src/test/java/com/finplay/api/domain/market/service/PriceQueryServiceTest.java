package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.CryptoPriceDto;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceQueryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 0, 0);

	@Test
	void getOrderExecutionPriceReturnsStockQuoteWithSameReplaySession() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 0L, true, NOW);
		StockReplaySession session = StockReplaySession.ready(
			NOW.toLocalDate(), NOW.toLocalDate().minusDays(1), NOW, NOW);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, session.getSourceTradingDate(), new BigDecimal("71000"), NOW, session));
		PriceQueryService service = new PriceQueryService(
			mock(InstrumentRepository.class), stockPriceProvider, mock(PriceStore.class),
			mock(TutorialSampleInstrumentPriceService.class));

		OrderExecutionPriceDto result = service.getOrderExecutionPrice(instrument);

		assertThat(result.priceQuote().price()).isEqualByComparingTo("71000");
		assertThat(result.stockReplaySession()).isSameAs(session);
	}

	@Test
	void getOrderExecutionPriceReturnsCryptoQuoteWithoutReplaySession() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService service = new PriceQueryService(
			mock(InstrumentRepository.class), mock(StockPriceProvider.class), priceStore,
			mock(TutorialSampleInstrumentPriceService.class));

		OrderExecutionPriceDto result = service.getOrderExecutionPrice(instrument);

		assertThat(result.priceQuote().price()).isEqualByComparingTo("50000000");
		assertThat(result.stockReplaySession()).isNull();
	}

	@Test
	void getOrderExecutionPriceFailsWhenOpenStockQuoteHasNoReplaySession() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 0L, true, NOW);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, NOW.toLocalDate(), new BigDecimal("71000"), NOW, null));
		PriceQueryService service = new PriceQueryService(
			mock(InstrumentRepository.class), stockPriceProvider, mock(PriceStore.class),
			mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> service.getOrderExecutionPrice(instrument))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));
	}

	@Test
	void getPriceReturnsAvailableQuoteWhenStockProviderHasPrice() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, LocalDate.of(2026, 7, 27), new BigDecimal("71000"),
			LocalDateTime.of(2026, 7, 27, 15, 30));
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPrice(1L);

		assertThat(result.price()).isEqualTo(new BigDecimal("71000"));
		assertThat(result.sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 27, 15, 30));
		assertThat(result.sourceTradingDate()).isEqualTo(LocalDate.of(2026, 7, 27));
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		verifyNoInteractions(priceStore);
	}

	@Test
	void getPriceThrowsPriceUnavailableWhenStockProviderHasNoPrice() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		StockReplayPriceDto quote = new StockReplayPriceDto(false, StockMarketStatus.CLOSED, null, null, null);
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPrice(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void getPriceReturnsAvailableQuoteWhenCryptoPriceStoreHasLatestPrice() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPrice(2L);

		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(NOW);
		assertThat(result.sourceTradingDate()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getPriceThrowsPriceUnavailableWhenCryptoPriceStoreReportsUnavailable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPrice(2L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void getPriceThrowsPriceUnavailableWhenCryptoConnectionAliveButTickNeverReceived() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC")).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPrice(2L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void getPriceThrowsNotFoundWhenInstrumentMissing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPrice(999L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(priceStore);
	}

	@Test
	void getPriceQuoteReturnsAvailableQuoteWhenStockProviderHasPriceWithoutThrowing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, LocalDate.of(2026, 7, 28), new BigDecimal("71000"),
			LocalDateTime.of(2026, 7, 28, 9, 5));
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(1L);

		assertThat(result.price()).isEqualTo(new BigDecimal("71000"));
		assertThat(result.sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 28, 9, 5));
		assertThat(result.sourceTradingDate()).isEqualTo(LocalDate.of(2026, 7, 28));
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
	}

	@Test
	void getPriceQuoteReturnsUnavailableQuoteWithoutThrowingWhenStockProviderHasNoPrice() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		StockReplayPriceDto quote = new StockReplayPriceDto(
			false, StockMarketStatus.CLOSED, LocalDate.of(2026, 7, 27), null, null);
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(1L);

		assertThat(result.price()).isNull();
		assertThat(result.sourceTime()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.UNAVAILABLE);
	}

	@Test
	void getPriceQuoteReturnsAvailableQuoteWhenCryptoPriceStoreHasLatestPriceWithoutThrowing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.sourceTradingDate()).isNull();
	}

	@Test
	void getPriceQuoteReturnsUnavailableQuoteWithoutThrowingWhenCryptoPriceStoreReportsUnavailable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.price()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void getPriceQuoteReturnsAvailableQuoteWithLastKnownPriceEvenThoughObservedLongAgo() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		LocalDateTime longAgo = NOW.minusHours(3);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), longAgo)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(longAgo);
	}

	@Test
	void getPriceQuoteReturnsUnavailableQuoteWhenCryptoConnectionAliveButTickNeverReceived() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC")).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(result.price()).isNull();
		assertThat(result.sourceTime()).isNull();
	}

	@Test
	void getPriceDoesNotThrowAndReturnsAvailableQuoteWhenCryptoConnectionAliveButObservedLongAgo() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		LocalDateTime longAgo = NOW.minusHours(3);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), longAgo)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPrice(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(longAgo);
	}

	@Test
	void getOrderExecutionPriceReturnsLastKnownPriceEvenThoughObservedLongAgo() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		LocalDateTime longAgo = NOW.minusHours(3);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), longAgo)));
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), mock(StockPriceProvider.class), priceStore,
			mock(TutorialSampleInstrumentPriceService.class));

		OrderExecutionPriceDto result = priceQueryService.getOrderExecutionPrice(instrument);

		assertThat(result.priceQuote().status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.priceQuote().price()).isEqualByComparingTo("50000000");
		assertThat(result.stockReplaySession()).isNull();
	}

	@Test
	void getPriceQuoteReturnsAvailableWhenObservedAtIsFreshEvenThoughReceivedAtIsStale() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		LocalDateTime staleReceivedAt = NOW.minusSeconds(30);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), staleReceivedAt, NOW)));
		when(priceStore.isStale(staleReceivedAt)).thenReturn(true);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(staleReceivedAt);
	}

	@Test
	void getOrderExecutionPriceReturnsLastKnownPriceWithoutThrowingWhenObservedAtIsAlsoOld() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		LocalDateTime oldReceivedAt = NOW.minusHours(3);
		LocalDateTime oldObservedAt = NOW.minusHours(2);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC")).thenReturn(
			Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), oldReceivedAt, oldObservedAt)));
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), mock(StockPriceProvider.class), priceStore,
			mock(TutorialSampleInstrumentPriceService.class));

		OrderExecutionPriceDto result = priceQueryService.getOrderExecutionPrice(instrument);

		assertThat(result.priceQuote().status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.priceQuote().price()).isEqualByComparingTo("50000000");
		assertThat(result.stockReplaySession()).isNull();
	}

	@Test
	void getOrderExecutionPriceStillThrowsPriceUnavailableWhenCryptoConnectionIsDisconnected() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), mock(StockPriceProvider.class), priceStore,
			mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getOrderExecutionPrice(instrument))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void getOrderExecutionPriceStillThrowsPriceUnavailableWhenCryptoTickNeverReceived() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC")).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), mock(StockPriceProvider.class), priceStore,
			mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getOrderExecutionPrice(instrument))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void getPriceQuoteThrowsNotFoundWhenInstrumentMissing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPriceQuote(999L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void getPriceStillThrowsPriceUnavailableAfterDelegatingToGetPriceQuote() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		StockReplayPriceDto quote = new StockReplayPriceDto(false, StockMarketStatus.CLOSED, null, null, null);
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThat(priceQueryService.getPriceQuote(1L).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThatThrownBy(() -> priceQueryService.getPrice(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void getPriceQuotesForStockDelegatesToProviderBatchMethodOnceAndPreservesOrder() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument first = Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true,
			NOW);
		Instrument second = Instrument.create(Market.STOCK, "000660", "SK하이닉스", BigDecimal.valueOf(100), 80000L, true,
			NOW);
		StockReplayPriceDto firstQuote = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, LocalDate.of(2026, 7, 27), new BigDecimal("71000"),
			LocalDateTime.of(2026, 7, 27, 15, 30));
		StockReplayPriceDto secondQuote = new StockReplayPriceDto(false, StockMarketStatus.CLOSED, null, null, null);
		when(stockPriceProvider.getCurrentPrices(any())).thenReturn(List.of(firstQuote, secondQuote));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(first, second));

		assertThat(results).hasSize(2);
		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("71000"));
		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(1).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(stockPriceProvider, times(1)).getCurrentPrices(any());
		verify(stockPriceProvider, never()).getCurrentPrice(any());
	}

	@Test
	void getPriceQuotesForStockMapsSameContractAsSingleGetPriceQuoteForEachInstrument() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, LocalDate.of(2026, 7, 28), new BigDecimal("71500"),
			LocalDateTime.of(2026, 7, 28, 10, 0));
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		when(stockPriceProvider.getCurrentPrices(any())).thenReturn(List.of(quote));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto viaSingle = priceQueryService.getPriceQuote(instrument);
		PriceQuoteDto viaBatch = priceQueryService.getPriceQuotes(List.of(instrument)).get(0);

		assertThat(viaBatch).isEqualTo(viaSingle);
	}

	@Test
	void getPriceQuotesForCryptoQueriesConnectionStatusOnceAndMapsMissingSymbolAsUnavailable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, NOW);
		Instrument eth = Instrument.create(Market.CRYPTO, "ETH", "이더리움", BigDecimal.valueOf(1000), 6000L, true, NOW);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		when(priceStore.getLatestPrice("ETH")).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc, eth));

		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(1).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(priceStore, times(1)).getConnectionStatus();
		verify(priceStore, never()).isPriceAvailable(any());
	}

	@Test
	void getPriceQuotesReturnsAvailableQuoteWithLastKnownPriceEvenThoughObservedLongAgo() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, NOW);
		LocalDateTime longAgo = NOW.minusHours(3);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), longAgo)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc));

		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(results.get(0).sourceTime()).isEqualTo(longAgo);
	}

	@Test
	void getPriceQuotesReturnsUnavailableQuoteForAllInstrumentsWhenCryptoConnectionIsDisconnected() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, NOW);
		Instrument eth = Instrument.create(Market.CRYPTO, "ETH", "이더리움", BigDecimal.valueOf(1000), 6000L, true, NOW);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc, eth));

		assertThat(results).extracting(PriceQuoteDto::status)
			.containsExactly(PriceStatus.UNAVAILABLE, PriceStatus.UNAVAILABLE);
		assertThat(results).allSatisfy(quote -> assertThat(quote.price()).isNull());
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void getPriceQuotesReturnsUnavailableQuoteWhenCryptoConnectionAliveButTickNeverReceived() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, NOW);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC")).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc));

		assertThat(results.get(0).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		assertThat(results.get(0).price()).isNull();
		assertThat(results.get(0).sourceTime()).isNull();
	}

	@Test
	void getPriceQuotesReturnsEmptyListWithoutTouchingProvidersWhenInstrumentsIsEmpty() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of());

		assertThat(results).isEmpty();
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	@Test
	void getPriceQuotesThrowsIllegalArgumentExceptionWhenMarketsAreMixed() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));
		Instrument stock = Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true,
			NOW);
		Instrument crypto = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);

		assertThatThrownBy(() -> priceQueryService.getPriceQuotes(List.of(stock, crypto)))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	@Test
	void getPriceQuoteForTutorialSampleInstrumentDelegatesToSampleServiceWithoutTouchingRealProviders() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService = mock(
			TutorialSampleInstrumentPriceService.class);
		Instrument sample = tutorialSampleInstrument(Market.STOCK, 1L);
		PriceQuoteDto sampleQuote = new PriceQuoteDto(new BigDecimal("51000.00000000"), NOW, PriceStatus.AVAILABLE,
			null);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sample)).thenReturn(sampleQuote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, tutorialSampleInstrumentPriceService);

		PriceQuoteDto result = priceQueryService.getPriceQuote(sample);

		assertThat(result).isEqualTo(sampleQuote);
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	@Test
	void getOrderExecutionPriceForStockTutorialSampleInstrumentSkipsStockPriceProviderAndReturnsNullReplaySession() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService = mock(
			TutorialSampleInstrumentPriceService.class);
		Instrument sample = tutorialSampleInstrument(Market.STOCK, 1L);
		PriceQuoteDto sampleQuote = new PriceQuoteDto(new BigDecimal("50500.00000000"), NOW, PriceStatus.AVAILABLE,
			null);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sample)).thenReturn(sampleQuote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, tutorialSampleInstrumentPriceService);

		OrderExecutionPriceDto result = priceQueryService.getOrderExecutionPrice(sample);

		assertThat(result.priceQuote()).isEqualTo(sampleQuote);
		assertThat(result.stockReplaySession()).isNull();
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	@Test
	void getOrderExecutionPriceForCryptoTutorialSampleInstrumentSkipsPriceStoreAndReturnsNullReplaySession() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService = mock(
			TutorialSampleInstrumentPriceService.class);
		Instrument sample = tutorialSampleInstrument(Market.CRYPTO, 4L);
		PriceQuoteDto sampleQuote = new PriceQuoteDto(new BigDecimal("10100.00000000"), NOW, PriceStatus.AVAILABLE,
			null);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sample)).thenReturn(sampleQuote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, tutorialSampleInstrumentPriceService);

		OrderExecutionPriceDto result = priceQueryService.getOrderExecutionPrice(sample);

		assertThat(result.priceQuote()).isEqualTo(sampleQuote);
		assertThat(result.stockReplaySession()).isNull();
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	@Test
	void getPriceQuotesMergesSampleAndRealStockInstrumentsInOriginalOrder() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService = mock(
			TutorialSampleInstrumentPriceService.class);
		Instrument realFirst = Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true,
			NOW);
		Instrument sample = tutorialSampleInstrument(Market.STOCK, 1L);
		Instrument realSecond = Instrument.create(Market.STOCK, "000660", "SK하이닉스", BigDecimal.valueOf(100), 80000L,
			true, NOW);
		StockReplayPriceDto realFirstQuote = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, LocalDate.of(2026, 7, 28), new BigDecimal("71000"),
			LocalDateTime.of(2026, 7, 28, 10, 0));
		StockReplayPriceDto realSecondQuote = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, LocalDate.of(2026, 7, 28), new BigDecimal("72000"),
			LocalDateTime.of(2026, 7, 28, 10, 0));
		when(stockPriceProvider.getCurrentPrices(any())).thenReturn(List.of(realFirstQuote, realSecondQuote));
		PriceQuoteDto sampleQuote = new PriceQuoteDto(new BigDecimal("51000.00000000"), NOW, PriceStatus.AVAILABLE,
			null);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sample)).thenReturn(sampleQuote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, tutorialSampleInstrumentPriceService);

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(realFirst, sample, realSecond));

		assertThat(results).hasSize(3);
		assertThat(results.get(0).price()).isEqualByComparingTo("71000");
		assertThat(results.get(1)).isEqualTo(sampleQuote);
		assertThat(results.get(2).price()).isEqualByComparingTo("72000");
		verifyNoInteractions(priceStore);
	}

	@Test
	void getPriceQuotesForAllSampleInstrumentsNeverTouchesStockPriceProviderOrPriceStore() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService = mock(
			TutorialSampleInstrumentPriceService.class);
		Instrument sampleStock = tutorialSampleInstrument(Market.STOCK, 1L);
		Instrument sampleCrypto = tutorialSampleInstrument(Market.CRYPTO, 4L);
		PriceQuoteDto sampleStockQuote = new PriceQuoteDto(new BigDecimal("51000.00000000"), NOW, PriceStatus.AVAILABLE,
			null);
		PriceQuoteDto sampleCryptoQuote = new PriceQuoteDto(new BigDecimal("10100.00000000"), NOW,
			PriceStatus.AVAILABLE,
			null);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sampleStock)).thenReturn(sampleStockQuote);
		when(tutorialSampleInstrumentPriceService.getPriceQuote(sampleCrypto)).thenReturn(sampleCryptoQuote);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, tutorialSampleInstrumentPriceService);

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(sampleStock, sampleCrypto));

		assertThat(results).containsExactly(sampleStockQuote, sampleCryptoQuote);
		verifyNoInteractions(stockPriceProvider, priceStore);
	}

	private Instrument tutorialSampleInstrument(Market market, long id) {
		Instrument instrument = Instrument.create(market, "SANDBOX_" + market + "_" + id, "연습용",
			BigDecimal.ONE, 10000L, true, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(instrument, "id", id);
		org.springframework.test.util.ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

}
