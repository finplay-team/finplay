package com.finplay.api.domain.education.synthetic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyntheticPriceServiceTest {

	private static final long INSTRUMENT_ID = 10L;
	private InstrumentService instrumentService;
	private PriceQueryService priceQueryService;
	private SyntheticPriceService service;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		instrumentService = mock(InstrumentService.class);
		priceQueryService = mock(PriceQueryService.class);
		service = new SyntheticPriceService(instrumentService, priceQueryService);
		instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 1L, true, LocalDateTime.now());
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
	}

	@Test
	void generateSeriesReturnsTitleTickSecondsAndHundredPricesWithinBoundedStepChanges() {
		BigDecimal startPrice = BigDecimal.valueOf(10_000);
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(startPrice, null, PriceStatus.AVAILABLE, null));

		SyntheticPriceSeriesResponse result = service.generateSeries(INSTRUMENT_ID);

		assertThat(result.title()).isEqualTo("삼성전자");
		assertThat(result.tickSeconds()).isEqualTo(3);
		assertThat(result.prices()).hasSize(100);
		assertThat(result.prices().get(0)).isEqualByComparingTo(startPrice);
		BigDecimal floor = startPrice.multiply(BigDecimal.valueOf(0.5));
		List<BigDecimal> prices = result.prices();
		for (int i = 1; i < prices.size(); i++) {
			BigDecimal previous = prices.get(i - 1);
			BigDecimal current = prices.get(i);
			assertThat(current).isGreaterThanOrEqualTo(floor);
			if (current.compareTo(floor) > 0) {
				BigDecimal ratio = current.divide(previous, 6, java.math.RoundingMode.HALF_UP);
				assertThat(ratio.doubleValue()).isBetween(0.98, 1.02);
			}
		}
	}

	@Test
	void generateSeriesUsesFallbackStartPriceWhenPriceUnavailable() {
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));

		SyntheticPriceSeriesResponse result = service.generateSeries(INSTRUMENT_ID);

		assertThat(result.prices().get(0)).isEqualByComparingTo(BigDecimal.valueOf(10_000));
	}

	@Test
	void generateSeriesUsesRealPriceEvenWhenObservationIsHoursOldButStatusIsAvailable() {
		when(priceQueryService.getPriceQuote(instrument))
			.thenReturn(new PriceQuoteDto(
				new BigDecimal("54321"), LocalDateTime.now().minusHours(3), PriceStatus.AVAILABLE, null));

		SyntheticPriceSeriesResponse result = service.generateSeries(INSTRUMENT_ID);

		assertThat(result.prices().get(0)).isEqualByComparingTo(new BigDecimal("54321"));
	}

	@Test
	void generateSeriesPropagatesInstrumentNotFound() {
		BusinessException notFound = new BusinessException(ErrorCode.NOT_FOUND);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenThrow(notFound);

		assertThatThrownBy(() -> service.generateSeries(INSTRUMENT_ID)).isSameAs(notFound);
	}
}
