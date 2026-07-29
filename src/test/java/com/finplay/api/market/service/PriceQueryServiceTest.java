// StockPriceProvider·PriceStore 구현체를 mock으로 대체해 PriceQueryService의 가격 매핑·장애 판정·계약 동등성을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.CryptoPriceDto;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PriceQueryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 0, 0);

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
			priceStore);

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
			priceStore);

		assertThatThrownBy(() -> priceQueryService.getPrice(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	// KRX_REPLAY 스타일: 재생 세션 기반이라 과거 거래일(sourceTradingDate)이 채워진 채로 종가를 돌려주는 구현을 흉내낸다.
	@Test
	void getPriceMapsSameContractForKrxReplayStyleProvider() {
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.CLOSED, LocalDate.of(2026, 7, 27), new BigDecimal("71000"),
			LocalDateTime.of(2026, 7, 27, 15, 30));
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), stockPriceProvider, mock(PriceStore.class));

		PriceQuoteDto result = priceQueryService.getPrice(instrument);

		assertThat(result.price()).isEqualTo(quote.price());
		assertThat(result.sourceTime()).isEqualTo(quote.sourceTime());
		assertThat(result.sourceTradingDate()).isEqualTo(quote.sourceTradingDate());
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
	}

	// KIS_REALTIME 스타일: 실시간 체결 틱 기반이라 재생 거래일 개념이 없어 sourceTradingDate를 null로 돌려주는 구현을 흉내낸다.
	@Test
	void getPriceMapsSameContractForKisRealtimeStyleProvider() {
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		StockReplayPriceDto quote = new StockReplayPriceDto(
			true, StockMarketStatus.OPEN, null, new BigDecimal("71500"), LocalDateTime.of(2026, 7, 28, 10, 0));
		when(stockPriceProvider.getCurrentPrice(any())).thenReturn(quote);
		PriceQueryService priceQueryService = new PriceQueryService(
			mock(InstrumentRepository.class), stockPriceProvider, mock(PriceStore.class));

		PriceQuoteDto result = priceQueryService.getPrice(instrument);

		assertThat(result.price()).isEqualTo(quote.price());
		assertThat(result.sourceTime()).isEqualTo(quote.sourceTime());
		assertThat(result.sourceTradingDate()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.AVAILABLE);
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
			priceStore);

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
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

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
			priceStore);

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
			priceStore);

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
			priceStore);

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
			priceStore);

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
			priceStore);

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
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

		PriceQuoteDto result = priceQueryService.getPriceQuote(2L);

		assertThat(result.price()).isNull();
		assertThat(result.status()).isEqualTo(PriceStatus.UNAVAILABLE);
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void getPriceQuoteThrowsNotFoundWhenInstrumentMissing() {
		// 가격 없음(UNAVAILABLE)과 종목 자체가 없음(NOT_FOUND)은 다른 문제다 — getPriceQuote도 종목 조회 실패는 여전히 예외로 던진다.
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

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
			priceStore);

		// getPriceQuote 자체는 UNAVAILABLE을 반환할 뿐 예외를 던지지 않는다.
		assertThat(priceQueryService.getPriceQuote(1L).status()).isEqualTo(PriceStatus.UNAVAILABLE);
		// 반면 getPrice는 동일 상황에서 여전히 409 PRICE_UNAVAILABLE 예외를 던진다 (기존 계약 유지).
		assertThatThrownBy(() -> priceQueryService.getPrice(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));
	}

	@Test
	void assertOrderablePassesWhenStockMarketIsOpen() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.OPEN);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

		priceQueryService.assertOrderable(instrument);

		verifyNoInteractions(priceStore);
	}

	@Test
	void assertOrderableThrowsMarketClosedWhenStockMarketIsClosed() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
		when(stockPriceProvider.getMarketStatus()).thenReturn(StockMarketStatus.CLOSED);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

		assertThatThrownBy(() -> priceQueryService.assertOrderable(instrument))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));
	}

	@Test
	void assertOrderableAlwaysPassesForCryptoRegardlessOfStockMarketStatus() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true,
			NOW);
		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore);

		priceQueryService.assertOrderable(instrument);

		verifyNoInteractions(stockPriceProvider, priceStore);
	}
}
