// 튜토리얼 합성 시세 생성의 성공 경로(틱 수·변동폭·clamp)와 가격 조회 실패 시 fallback 시작가 사용을 검증하는 단위 테스트다.
package com.finplay.api.education.synthetic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
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
			// 틱당 변동폭은 이전 값 대비 -1%~+1% 이내여야 하며, floor clamp가 적용된 경우는 예외로 허용한다.
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

	// 036-remove-crypto-stale-status 회귀 — 관측 시각이 오래돼도(과거엔 STALE) AVAILABLE이면 fallback이 아니라
	// 실시세를 시작가로 쓴다.
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
