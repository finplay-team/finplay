// 코인 캔들 조회(CandleQueryService/CryptoCandleProvider)와 코인 현재가 조회(PriceQueryService/PriceStore)가
// 완전히 독립된 경로임을 같은 종목에 대해 직접 고정하는 테스트 (MKT-008, 이슈 #20 — plan.md "현재가와의 관계")
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
		// Redis(PriceStore)가 비어 있거나 연결이 끊긴 상태(WebSocket 장애)를 흉내낸다.
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

		// 현재가 경로는 PriceStore 장애로 실패해야 한다.
		assertThatThrownBy(() -> priceQueryService.getPrice(BTC_INSTRUMENT_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRICE_UNAVAILABLE));

		// 그럼에도 캔들 조회 경로는 완전히 독립적이므로 정상 성공해야 한다.
		CandleListResponse response = candleQueryService.getCandles(BTC_INSTRUMENT_ID, "1m", null, null, null);
		assertThat(response.content()).hasSize(1);
	}

	@Test
	void priceQueryStillSucceedsWhenCryptoCandleProviderFails() {
		// 빗썸 캔들 REST가 죽어도(타임아웃·502) 현재가·주문 경로(PriceStore 기반)는 영향받지 않는다.
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		when(instrumentRepository.findById(BTC_INSTRUMENT_ID)).thenReturn(Optional.of(btcInstrument()));
		StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
		PriceStore priceStore = mock(PriceStore.class);
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
		// getCryptoDisplayPriceQuote(표시 경로, PriceQueryService.getPrice가 위임)가 연결상태를 먼저 확인한다
		// (032 PRICE-STALE-001, PR #360 리뷰 권장사항 후속 — race window 제거를 위해 isPriceAvailable 위임 대신
		// getConnectionStatus()+getLatestPrice()를 직접 조합하도록 바뀌면서 이 stub이 필요해졌다. 단정은 불변).
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

		// 캔들 조회 경로는 빗썸 장애로 502가 돼야 한다.
		assertThatThrownBy(() -> candleQueryService.getCandles(BTC_INSTRUMENT_ID, "1m", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		// 그럼에도 현재가 조회 경로는 완전히 독립적이므로 정상 성공해야 한다.
		var price = priceQueryService.getPrice(BTC_INSTRUMENT_ID);
		assertThat(price.price()).isEqualByComparingTo("50000000");
	}
}
