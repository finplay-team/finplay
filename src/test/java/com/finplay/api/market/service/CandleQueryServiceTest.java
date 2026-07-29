// 캔들 API 요청 검증 순서(interval → from/to → instrumentId 존재 → 시장 종류)와 provider 위임을 검증하는 단위 테스트
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
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CandleQueryServiceTest {

	private static final Long STOCK_INSTRUMENT_ID = 1L;
	private static final Long CRYPTO_INSTRUMENT_ID = 17L;

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
	private final CandleQueryService service = new CandleQueryService(instrumentRepository, stockPriceProvider);

	private static Instrument stockInstrument() {
		return Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 70000L, true, LocalDateTime.now());
	}

	private static Instrument cryptoInstrument() {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5000L, true, LocalDateTime.now());
	}

	@Test
	void getCandlesRejectsUnsupportedIntervalBeforeTouchingRepositoryOrProvider() {
		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "5m", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesRejectsFromAfterToBeforeTouchingRepositoryOrProvider() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 10, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 0);

		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesRejectsWhenFromTimeIsAfterToTimeEvenIfFromDateIsEarlier() {
		// 리뷰 확정(PR #87 QA FAIL 옵션 c): from>to 판정은 날짜가 아니라 시각(LocalTime)만 비교한다.
		// 날짜만 보면 from(07-22)이 to(07-23)보다 이르지만, 시각은 09:01 > 09:00이므로 400이어야 한다.
		LocalDateTime from = LocalDateTime.of(2026, 7, 22, 9, 1);
		LocalDateTime to = LocalDateTime.of(2026, 7, 23, 9, 0);

		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesAllowsFromLaterByDateThanToWhenFromTimeIsNotAfterToTime() {
		// 날짜만 보면 from(07-23)이 to(07-22)보다 늦지만, 시각은 09:00 <= 09:01이므로 통과해야 한다(날짜 성분은 무시).
		LocalDateTime from = LocalDateTime.of(2026, 7, 23, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 22, 9, 1);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, from, to)).thenReturn(List.of());

		List<CandleResponse> result = service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to);

		assertThat(result).isEmpty();
	}

	@Test
	void getCandlesAllowsFromEqualToTo() {
		LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 27, 9, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, sameInstant, sameInstant)).thenReturn(List.of());

		List<CandleResponse> result = service.getCandles(STOCK_INSTRUMENT_ID, "1m", sameInstant, sameInstant);

		assertThat(result).isEmpty();
	}

	@Test
	void getCandlesThrowsNotFoundWhenInstrumentDoesNotExist() {
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getCandles(999L, "1m", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesRejectsCryptoInstrumentWithValidationError() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));

		assertThatThrownBy(() -> service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(stockPriceProvider, never()).getCandles(any(), any(), any());
	}

	@Test
	void getCandlesDelegatesToStockPriceProviderAndMapsToResponseForStockInstrument() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDate tradingDate = LocalDate.of(2026, 7, 27);
		StockCandleDto candleDto = new StockCandleDto(
			tradingDate, LocalTime.of(9, 0), BigDecimal.valueOf(70000), BigDecimal.valueOf(70500),
			BigDecimal.valueOf(69900), BigDecimal.valueOf(70200), 12345L);
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, null, null)).thenReturn(List.of(candleDto));

		List<CandleResponse> result = service.getCandles(STOCK_INSTRUMENT_ID, "1m", null, null);

		assertThat(result).hasSize(1);
		CandleResponse response = result.get(0);
		assertThat(response.sourceTime()).isEqualTo(LocalDateTime.of(tradingDate, LocalTime.of(9, 0)));
		assertThat(response.open()).isEqualByComparingTo("70000");
		assertThat(response.high()).isEqualByComparingTo("70500");
		assertThat(response.low()).isEqualByComparingTo("69900");
		assertThat(response.close()).isEqualByComparingTo("70200");
		assertThat(response.volume()).isEqualTo(12345L);
	}

	@Test
	void getCandlesPassesFromAndToThroughToProviderUnchanged() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 5);
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, from, to)).thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to);

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, from, to);
	}
}
