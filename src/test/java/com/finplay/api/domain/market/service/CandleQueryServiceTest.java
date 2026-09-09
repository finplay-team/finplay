package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.CandleResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandleQueryServiceTest {

	private static final Long STOCK_INSTRUMENT_ID = 1L;
	private static final Long CRYPTO_INSTRUMENT_ID = 17L;

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
	private final CryptoCandleProvider cryptoCandleProvider = mock(CryptoCandleProvider.class);
	private final CandleQueryService service = new CandleQueryService(
		instrumentRepository, stockPriceProvider, cryptoCandleProvider);

	private static Instrument stockInstrument() {
		return Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 70000L, true, LocalDateTime.now());
	}

	private static Instrument cryptoInstrument() {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5000L, true, LocalDateTime.now());
	}

	private static List<CryptoCandleDto> ascendingCryptoCandles(int count, LocalDateTime oldest) {
		return IntStream.range(0, count)
			.mapToObj(i -> new CryptoCandleDto(
				oldest.plusMinutes(i), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.ONE))
			.toList();
	}

	private static List<StockCandleDto> ascendingStockCandles(int count, LocalDate tradingDate, LocalTime oldest) {
		return IntStream.range(0, count)
			.mapToObj(i -> new StockCandleDto(
				tradingDate, oldest.plusMinutes(i), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
				1L))
			.toList();
	}

	@Test
	void getCandlesRejectsUnsupportedIntervalBeforeTouchingRepositoryOrProvider() {
		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "5m", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesThrowsNotFoundWhenInstrumentDoesNotExist() {
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getCandles(999L, "1m", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesRejectsStockFromAfterToAfterLookingUpInstrumentButBeforeTouchingProvider() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 10, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));

		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(instrumentRepository).findById(STOCK_INSTRUMENT_ID);
		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesRejectsWhenFromTimeIsAfterToTimeEvenIfFromDateIsEarlier() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 22, 9, 1);
		LocalDateTime to = LocalDateTime.of(2026, 7, 23, 9, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));

		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesAllowsFromLaterByDateThanToWhenFromTimeIsNotAfterToTime() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 23, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 22, 9, 1);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, from, to))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void getCandlesAllowsFromEqualToTo() {
		LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 27, 9, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, sameInstant, sameInstant))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1m", sameInstant, sameInstant, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void getCandlesDelegatesToStockPriceProviderAndMapsToResponseForStockInstrument() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDate tradingDate = LocalDate.of(2026, 7, 27);
		StockCandleDto candleDto = new StockCandleDto(
			tradingDate, LocalTime.of(9, 0), BigDecimal.valueOf(70000), BigDecimal.valueOf(70500),
			BigDecimal.valueOf(69900), BigDecimal.valueOf(70200), 12345L);
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of(candleDto));

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(response.content()).hasSize(1);
		CandleResponse candle = response.content().get(0);
		assertThat(candle.sourceTime()).isEqualTo(LocalDateTime.of(tradingDate, LocalTime.of(9, 0)));
		assertThat(candle.open()).isEqualByComparingTo("70000");
		assertThat(candle.high()).isEqualByComparingTo("70500");
		assertThat(candle.low()).isEqualByComparingTo("69900");
		assertThat(candle.close()).isEqualByComparingTo("70200");
		assertThat(candle.volume()).isEqualByComparingTo(BigDecimal.valueOf(12345L));
	}

	@Test
	void getCandlesPassesFromAndToThroughToStockProviderUnchanged() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 5);
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, from, to))
			.thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1m", from, to, null);

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, from, to);
	}

	@Test
	void getCandlesRejectsStockAggregatedFromAfterToByDateEvenWhenFromTimeIsEarlier() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 28, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 10, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));

		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1d", from, to, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(instrumentRepository).findById(STOCK_INSTRUMENT_ID);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesAllowsStockAggregatedFromAndToOnSameDateRegardlessOfTimeComponent() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 23, 59);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 0, 0);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_DAY, from, to))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1d", from, to, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void getCandlesAllowsStockAggregatedFromEqualToToByDate() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 23, 59);
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_WEEK, from, to))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1w", from, to, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void getCandlesDelegatesToStockPriceProviderWithOneDayIntervalAndPassesFromToUnchanged() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 31, 0, 0);
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_DAY, from, to))
			.thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1d", from, to, null);

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_DAY, from, to);
	}

	@Test
	void getCandlesDelegatesToStockPriceProviderWithOneWeekInterval() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_WEEK, null, null))
			.thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1w", null, null, null);

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_WEEK, null, null);
	}

	@Test
	void getCandlesDelegatesToStockPriceProviderWithOneMonthInterval() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MONTH, null, null))
			.thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1M", null, null, null);

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MONTH, null, null);
	}

	@Test
	void getCandlesRejectsUppercaseDIntervalVariantBeforeTouchingRepositoryOrProvider() {
		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "1D", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesRejectsBlankIntervalBeforeTouchingRepositoryOrProvider() {
		assertThatThrownBy(() -> service.getCandles(STOCK_INSTRUMENT_ID, "", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesNoLongerRejectsCryptoInstrumentAndDelegatesToCryptoCandleProvider() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 30, 11, 43);
		CryptoCandleDto candleDto = new CryptoCandleDto(
			sourceTime, new BigDecimal("95000000"), new BigDecimal("95100000"), new BigDecimal("94900000"),
			new BigDecimal("95050000"), new BigDecimal("0.26725783"));
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of(candleDto));

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(response.content()).hasSize(1);
		CandleResponse candle = response.content().get(0);
		assertThat(candle.sourceTime()).isEqualTo(sourceTime);
		assertThat(candle.open()).isEqualByComparingTo("95000000");
		assertThat(candle.high()).isEqualByComparingTo("95100000");
		assertThat(candle.low()).isEqualByComparingTo("94900000");
		assertThat(candle.close()).isEqualByComparingTo("95050000");
		assertThat(candle.volume()).isEqualByComparingTo("0.26725783");
		verify(cryptoCandleProvider).getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);
		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void getCandlesPassesFromAndToThroughToCryptoCandleProviderUnchanged() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 11, 43);
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(List.of());

		service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, to, null);

		verify(cryptoCandleProvider).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
	}

	@Test
	void getCandlesRejectsCryptoFromAfterToOnSameDayBeforeTouchingCryptoCandleProvider() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 10, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 0);
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));

		assertThatThrownBy(() -> service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, to, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesAllowsCryptoFromWithEarlierDateEvenWhenTimeOfDayIsLater() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 20, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 31, 8, 0);
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to)).thenReturn(List.of());

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, to, null);

		assertThat(response.content()).isEmpty();
		verify(cryptoCandleProvider).getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);
	}

	@Test
	void getCandlesRejectsCryptoFromWithLaterDateEvenWhenTimeOfDayIsEarlier() {
		LocalDateTime from = LocalDateTime.of(2026, 7, 31, 8, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 20, 0);
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));

		assertThatThrownBy(() -> service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, to, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesAllowsCryptoFromEqualToTo() {
		LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 30, 9, 0);
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles("BTC", CandleInterval.ONE_MINUTE, sameInstant, sameInstant))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", sameInstant, sameInstant, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void getCandlesForCryptoDoesNotTouchAnyPriceRelatedComponent() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles(any(), any(), any(), any())).thenReturn(List.of());

		service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null);

		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void cryptoCandleProviderFailureDoesNotPreventFutureStockCandleQueries() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles(any(), any(), any(), any()))
			.thenThrow(new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		assertThatThrownBy(() -> service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of());

		CandleListResponse stockResponse = service.getCandles(STOCK_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(stockResponse.content()).isEmpty();
	}

	@Test
	void getCandlesRejectsCryptoInstrumentIsNoLongerThrownForValidRequest() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles(any(), any(), any(), any())).thenReturn(List.of());

		assertThat(service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null)).isNotNull();
		verify(stockPriceProvider, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	void getCandlesRejectsInvalidCursorFormatWithValidationError() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));

		assertThatThrownBy(
			() -> service.getCandles(STOCK_INSTRUMENT_ID, "1m", null, null, "not-a-valid-cursor"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesRejectsUnsupportedIntervalBeforeCursorValidationEvenWithInvalidCursor() {
		assertThatThrownBy(
			() -> service.getCandles(STOCK_INSTRUMENT_ID, "5m", null, null, "also-not-a-cursor"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
	}

	@Test
	void getCandlesThrowsNotFoundForMissingInstrumentEvenWithInvalidCursorFormat() {
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(
			() -> service.getCandles(999L, "1m", null, null, "not-a-valid-cursor"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(stockPriceProvider);
		verifyNoInteractions(cryptoCandleProvider);
	}

	@Test
	void getCandlesOverridesCryptoToWithCursorMinusOneMinuteIgnoringOriginalTo() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime from = LocalDateTime.of(2026, 7, 20, 0, 0);
		LocalDateTime ignoredTo = LocalDateTime.of(2026, 7, 25, 0, 0);
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 30, 9, 0);
		LocalDateTime expectedTo = cursor.minusMinutes(1);
		when(cryptoCandleProvider.getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), eq(from), any()))
			.thenReturn(List.of());

		service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, ignoredTo, cursor.toString());

		ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(cryptoCandleProvider).getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), eq(from), toCaptor.capture());
		assertThat(toCaptor.getValue()).isEqualTo(expectedTo);
	}

	@Test
	void getCandlesOverridesStockAggregatedToWithCursorMinusOneMinute() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 28, 0, 0);
		LocalDateTime expectedTo = cursor.minusMinutes(1);
		when(stockPriceProvider.getCandles(eq(STOCK_INSTRUMENT_ID), eq(CandleInterval.ONE_DAY), eq(null), any()))
			.thenReturn(List.of());

		service.getCandles(STOCK_INSTRUMENT_ID, "1d", null, null, cursor.toString());

		ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(stockPriceProvider)
			.getCandles(eq(STOCK_INSTRUMENT_ID), eq(CandleInterval.ONE_DAY), eq(null), toCaptor.capture());
		assertThat(toCaptor.getValue()).isEqualTo(expectedTo);
	}

	@Test
	void getCandlesDoesNotApplyCursorForStockOneMinuteAndPassesOriginalToUnchanged() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of());

		service.getCandles(
			STOCK_INSTRUMENT_ID, "1m", null, null, LocalDateTime.of(2026, 7, 27, 9, 0).toString());

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null);
	}

	@Test
	void getCandlesSetsHasNextTrueAndNextCursorToOldestCandleWhenContentIsFullPage() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime oldest = LocalDateTime.of(2026, 7, 1, 0, 0);
		List<CryptoCandleDto> fullPage = ascendingCryptoCandles(200, oldest);
		when(cryptoCandleProvider.getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenReturn(fullPage);

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(response.content()).hasSize(200);
		assertThat(response.hasNext()).isTrue();
		assertThat(response.nextCursor()).isEqualTo(CandleCursor.encode(oldest));
	}

	@Test
	void getCandlesSetsHasNextFalseAndNextCursorNullWhenContentIsUnderFullPage() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime oldest = LocalDateTime.of(2026, 7, 1, 0, 0);
		List<CryptoCandleDto> partialPage = ascendingCryptoCandles(5, oldest);
		when(cryptoCandleProvider.getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenReturn(partialPage);

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(response.content()).hasSize(5);
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getCandlesForcesHasNextFalseAndNextCursorNullForStockOneMinuteEvenWithFullPage() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDate tradingDate = LocalDate.of(2026, 7, 27);
		List<StockCandleDto> fullPage = ascendingStockCandles(200, tradingDate, LocalTime.of(9, 0));
		when(stockPriceProvider.getCandles(eq(STOCK_INSTRUMENT_ID), eq(CandleInterval.ONE_MINUTE), any(), any()))
			.thenReturn(fullPage);

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1m", null, null, null);

		assertThat(response.content()).hasSize(200);
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getCandlesReturns200WithHasNextFalseForStockOneMinuteWhenCursorIsGiven() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(
			STOCK_INSTRUMENT_ID, "1m", null, null, LocalDateTime.of(2026, 7, 27, 9, 0).toString());

		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getCandlesReturnsEmptyEnvelopeWithoutCallingCryptoProviderWhenFromIsAfterNormalizedCursorUpperBound() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 30, 9, 0);
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 9, 0);

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, null, cursor.toString());

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		verify(cryptoCandleProvider, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	void getCandlesReturnsEmptyEnvelopeWithoutCallingStockProviderWhenFromIsAfterNormalizedCursorUpperBoundForAggregated() {
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 28, 0, 0);
		LocalDateTime from = LocalDateTime.of(2026, 7, 28, 0, 0);

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1d", from, null, cursor.toString());

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		verify(stockPriceProvider, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	void getCandlesDoesNotEarlyReturnWhenFromEqualsNormalizedCursorUpperBound() {
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 30, 9, 0);
		LocalDateTime from = cursor.minusMinutes(1);
		when(cryptoCandleProvider.getCandles(eq("BTC"), eq(CandleInterval.ONE_MINUTE), eq(from), eq(from)))
			.thenReturn(List.of());

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, null, cursor.toString());

		assertThat(response.content()).isEmpty();
		verify(cryptoCandleProvider).getCandles("BTC", CandleInterval.ONE_MINUTE, from, from);
	}
}
