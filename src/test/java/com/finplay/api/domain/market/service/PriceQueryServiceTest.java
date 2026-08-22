// StockPriceProvider·PriceStore 구현체를 mock으로 대체해 PriceQueryService의 가격 매핑·장애 판정·계약 동등성을 검증하는 단위 테스트다.
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
		// isStale은 stub하지 않는다 — Mockito mock의 boolean 기본값 false가 곧 "fresh"라 AVAILABLE로 떨어진다.
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
		// isStale은 stub하지 않는다 — Mockito mock의 boolean 기본값 false가 곧 "fresh"라 AVAILABLE로 떨어진다.
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

	// 연결은 유지되지만 그 심볼의 시세를 한 번도 받은 적이 없는 경우도 방어적으로 PRICE_UNAVAILABLE 처리하는지 확인한다.
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

	// 이하 getPriceQuote(SSE snapshot·price 전용 경로, 이슈 #18) — 가격이 없어도 예외를 던지지 않고 status=UNAVAILABLE로 반환해야 한다.

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
		// isStale은 stub하지 않는다 — Mockito mock의 boolean 기본값 false가 곧 "fresh"라 AVAILABLE로 떨어진다.
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

	// 이하 PRICE-NOSTALE-001 — 연결 유지 + 수신 이력 있음이면 관측 시각이 얼마나 오래됐든 항상 AVAILABLE이다
	// (ai/specs/036-remove-crypto-stale-status, 032 PRICE-STALE-001의 stale 완화 자체를 되돌림 — STALE은
	// 더 이상 발생하지 않는다).

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

		// getPrice(Long)은 requireAvailable을 거치지만 관측 시각이 오래됐다는 이유만으로는 UNAVAILABLE이 아니므로
		// 예외를 던지지 않는다(PRICE-NOSTALE-001).
		PriceQuoteDto result = priceQueryService.getPrice(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(longAgo);
	}

	// 체결 경로(getOrderExecutionPrice)는 표시 경로와 같은 규칙을 쓴다 — 연결 유지 + 수신 이력 있음이면 관측 시각이
	// 얼마나 오래됐든 예외 없이 마지막 가격으로 체결된다(PRICE-NOSTALE-001, ai/specs/036-remove-crypto-stale-status).
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

	// 이하 PRICE-REST-001 판정 경로 회귀(034 tasks.md 항목 6) — observedAt(관측 시각)과 receivedAt(체결 시각)을
	// 서로 다른 값으로 넣어, 서비스가 실제로 isStale에 observedAt을 넘기는지 확인한다. receivedAt만 같던 기존
	// 테스트들은 이 배선(observedAt vs receivedAt) 오류를 잡지 못했다 — 4-arg CryptoPriceDto 생성자로 둘을 분리해야
	// 드러난다.

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
		// receivedAt(체결 시각, 30초 전)으로 isStale을 부르면 true가 나오도록 스텁한다 — observedAt(NOW, 신선)을
		// 넘기지 않고 receivedAt을 넘기는 배선 오류가 있으면 이 스텁 때문에 STALE로 잘못 떨어진다.
		when(priceStore.isStale(staleReceivedAt)).thenReturn(true);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(staleReceivedAt); // sourceTime은 여전히 체결 시각 그대로다
	}

	// 관측 시각(observedAt)도 체결 시각(receivedAt)과 함께 오래된 경우(REST 폴링도 조용한 것처럼)에도 체결
	// (getOrderExecutionPrice)은 예외 없이 마지막 가격을 반환한다(PRICE-NOSTALE-001) — isStale 자체를 더 이상
	// 호출하지 않으므로 observedAt·receivedAt 둘 다 얼마나 오래됐는지는 결과에 영향을 주지 않는다.
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

	// 이하 fail-closed 잔여선(PRICE-REST-005) — "가격이 오래된 것"과 "가격 자체가 없는 것"은 다른 상태이며,
	// 후자는 이번 완화 대상이 아니다.

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
		// 가격 없음(UNAVAILABLE)과 종목 자체가 없음(NOT_FOUND)은 다른 문제다 — getPriceQuote도 종목 조회 실패는 여전히 예외로 던진다.
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

	// getPrice(Long)의 409 PRICE_UNAVAILABLE 계약 회귀 확인 — getPriceQuote 위임으로 리팩터링된 이후에도
	// 반환된 UNAVAILABLE 상태를 getPrice가 여전히 예외로 승격시키는지 별도로 고정한다 (이슈 #18).
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

		// getPriceQuote 자체는 UNAVAILABLE을 반환할 뿐 예외를 던지지 않는다.
		assertThat(priceQueryService.getPriceQuote(1L).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		// 반면 getPrice는 동일 상황에서 여전히 409 PRICE_UNAVAILABLE 예외를 던진다 (기존 계약 유지).
		assertThatThrownBy(() -> priceQueryService.getPrice(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	// 이하 getPriceQuotes(List) — 배치 조회 (PR #97 리뷰 권장사항, 다음 이슈 #51 착수 전 정리).

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
		// 테스트 대상 Instrument는 persist하지 않아 getId()가 null이므로(List.of는 null 원소를 금지) any()로 매칭한다.
		when(stockPriceProvider.getCurrentPrices(any())).thenReturn(List.of(firstQuote, secondQuote));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(first, second));

		assertThat(results).hasSize(2);
		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("71000"));
		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(1).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		// 종목과 무관한 전역 상태 중복 조회를 없애는 것이 배치화의 목적이므로 배치 메서드는 요청당 1회만 호출돼야 한다.
		verify(stockPriceProvider, times(1)).getCurrentPrices(any());
		verify(stockPriceProvider, never()).getCurrentPrice(any());
	}

	@Test
	void getPriceQuotesForStockMapsSameContractAsSingleGetPriceQuoteForEachInstrument() {
		// 회귀 확인 — getPriceQuotes(배치)와 getPriceQuote(단건)는 같은 StockReplayPriceDto 입력에 대해
		// 동일한 PriceQuoteDto를 만들어야 한다(원가·평가금액 계산에 쓰이는 계약이 배치화로 달라지면 안 됨).
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
		// isStale은 stub하지 않는다 — Mockito mock의 boolean 기본값 false가 곧 "fresh"라 AVAILABLE로 떨어진다.
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc, eth));

		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(1).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		// 연결상태는 심볼 수와 무관하게 요청당 1회만 조회해야 한다(PR #97 최적화, tasks.md 항목 2 지시사항).
		verify(priceStore, times(1)).getConnectionStatus();
		verify(priceStore, never()).isPriceAvailable(any());
	}

	// 이하 PRICE-NOSTALE-001 배치판 — 단건 getCryptoDisplayPriceQuote와 동일한 규칙(연결 유지+수신 이력 있음이면
	// 경과 시간과 무관하게 항상 AVAILABLE, 연결 끊김·수신 이력 없음→UNAVAILABLE)을 배치에서도 확인한다
	// (ai/specs/036-remove-crypto-stale-status).

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
		// 연결이 끊기면 심볼별 최신가 조회 자체를 생략한다(불필요한 Redis 호출 방지).
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

	// 이하 샘플 종목(isTutorialSample=true) 분기 회귀 테스트 (이슈 #339, SANDBOX-002·003) —
	// stockPriceProvider·priceStore를 전혀 호출하지 않고 항상 AVAILABLE·OPEN을 반환해야 한다.

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
		// realFirst·realSecond는 persist하지 않아 getId()가 null이므로(List.of는 null 원소를 금지) any()로 매칭한다.
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

		// 샘플 종목만 있으면 market 혼재(STOCK+CRYPTO)여도 실제 종목 방어 검사(마켓 혼재 확인)를 타지 않아야 한다 —
		// 그 검사는 실제 종목 부분집합에만 적용되기 때문이다.
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
