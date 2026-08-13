// StockPriceProvider·PriceStore 구현체를 mock으로 대체해 PriceQueryService의 가격 매핑·장애 판정·계약 동등성을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.CryptoPriceDto;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
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
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
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
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		assertThatThrownBy(() -> priceQueryService.getPrice(2L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
		verify(priceStore, never()).getLatestPrice(any());
	}

	// isPriceAvailable=true 이후 조회 사이에 값이 사라지는 경합(레이스)까지 방어적으로 PRICE_UNAVAILABLE 처리하는지 확인한다.
	@Test
	void getPriceThrowsPriceUnavailableWhenCryptoLatestPriceMissingDespiteAvailableFlag() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
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
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
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
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.DISCONNECTED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.price()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(priceStore, never()).getLatestPrice(any());
	}

	// 이하 PRICE-STALE-001·003 — 표시 경로 stale 완화(connected+stale)와 체결 경로 무변경 (docs/specs/032-price-quote-stale-split).

	@Test
	void getPriceQuoteReturnsStaleQuoteWithLastKnownPriceWhenCryptoConnectionAliveButTickIsStale() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.STALE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(NOW);
	}

	@Test
	void getPriceQuoteReturnsUnavailableQuoteWhenCryptoConnectionAliveButTickNeverReceived() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
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
	void getPriceDoesNotThrowAndReturnsStaleQuoteWhenCryptoConnectionAliveButTickIsStale() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		when(instrumentRepository.findById(2L)).thenReturn(Optional.of(instrument));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		// getPrice(Long)은 requireAvailable을 거치지만 STALE은 UNAVAILABLE이 아니므로 예외를 던지지 않는다 (PRICE-STALE-001).
		PriceQuoteDto result = priceQueryService.getPrice(2L);

		assertThat(result.status()).isEqualTo(PriceStatus.STALE);
		assertThat(result.price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(result.sourceTime()).isEqualTo(NOW);
	}

	// 체결 경로(getOrderExecutionPrice)는 표시 경로가 완화되어도 stale이면 여전히 409를 던진다 (PRICE-STALE-003, MKT-004 fail-closed 무변경).
	@Test
	void getOrderExecutionPriceStillThrowsPriceUnavailableWhenCryptoTickIsStale() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 5000L, true, NOW);
		PriceStore priceStore = mock(PriceStore.class);
		// 체결 경로는 getCryptoExecutionPriceQuote(옛 getCryptoPriceQuote)를 그대로 쓰므로 isPriceAvailable=false만으로 충분하다 —
		// getConnectionStatus()는 호출되지 않는다(표시 경로 전용 판정).
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
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
	void getPriceQuotesForCryptoDelegatesToPriceStoreBatchMethodOnceAndMapsMissingSymbolAsUnavailable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, NOW);
		Instrument eth = Instrument.create(Market.CRYPTO, "ETH", "이더리움", BigDecimal.valueOf(1000), 6000L, true, NOW);
		when(priceStore.getLatestPrices(List.of("BTC", "ETH")))
			.thenReturn(Map.of("BTC", new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));

		List<PriceQuoteDto> results = priceQueryService.getPriceQuotes(List.of(btc, eth));

		assertThat(results.get(0).price()).isEqualTo(new BigDecimal("50000000"));
		assertThat(results.get(0).status()).isEqualTo(PriceStatus.AVAILABLE);
		assertThat(results.get(1).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(priceStore, times(1)).getLatestPrices(any());
		verify(priceStore, never()).getLatestPrice(any());
		verify(priceStore, never()).isPriceAvailable(any());
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
