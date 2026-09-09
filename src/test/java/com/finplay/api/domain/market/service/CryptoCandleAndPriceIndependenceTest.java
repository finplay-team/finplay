package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.CryptoPriceDto;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CryptoCandleAndPriceIndependenceTest {

	private static final Long BTC_INSTRUMENT_ID = 17L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 11, 43);

	private static Instrument btcInstrument() {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5000L, true, NOW);
	}

	@Test
	void candleQueryStillSucceedsWhenPriceStoreIsEmptyOrDisconnected() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		when(instrumentRepository.findById(BTC_INSTRUMENT_ID)).thenReturn(Optional.of(btcInstrument()));
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);
		CryptoCandleProvider cryptoCandleProvider = mock(CryptoCandleProvider.class);
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null)).thenReturn(List.of(
			new CryptoCandleDto(NOW, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));

		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));
		CandleQueryService candleQueryService = new CandleQueryService(instrumentRepository, stockPriceProvider,
			cryptoCandleProvider);

		assertThatThrownBy(() -> priceQueryService.getPrice(BTC_INSTRUMENT_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRICE_UNAVAILABLE));

		CandleListResponse response = candleQueryService.getCandles(BTC_INSTRUMENT_ID, "1m", null, null, null);
		assertThat(response.content()).hasSize(1);
	}

	@Test
	void priceQueryStillSucceedsWhenCryptoCandleProviderFails() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		when(instrumentRepository.findById(BTC_INSTRUMENT_ID)).thenReturn(Optional.of(btcInstrument()));
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
		when(priceStore.getConnectionStatus()).thenReturn(FeedConnectionStatus.CONNECTED);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW)));
		CryptoCandleProvider cryptoCandleProvider = mock(CryptoCandleProvider.class);
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.thenThrow(new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		PriceQueryService priceQueryService = new PriceQueryService(instrumentRepository, stockPriceProvider,
			priceStore, mock(TutorialSampleInstrumentPriceService.class));
		CandleQueryService candleQueryService = new CandleQueryService(instrumentRepository, stockPriceProvider,
			cryptoCandleProvider);

		assertThatThrownBy(() -> candleQueryService.getCandles(BTC_INSTRUMENT_ID, "1m", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		var price = priceQueryService.getPrice(BTC_INSTRUMENT_ID);
		assertThat(price.price()).isEqualByComparingTo("50000000");
	}
}
