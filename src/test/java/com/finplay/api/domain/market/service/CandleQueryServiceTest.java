// 캔들 API 요청 검증 순서(interval → instrumentId 존재 → 시장 판정 → 커서/from/to)와 커서 페이지네이션(048)을 검증하는 단위 테스트
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

	// --- 주식: 이번 변경(instrumentId 조회를 from/to 검증보다 먼저)으로 순서가 뒤바뀐 회귀 확인 ---

	@Test
	void getCandlesRejectsStockFromAfterToAfterLookingUpInstrumentButBeforeTouchingProvider() {
		// 순서 변경 회귀 확인: instrumentId 조회가 먼저 일어나므로 이제 instrumentRepository는 호출된다.
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
		// 리뷰 확정(PR #87 QA FAIL 옵션 c): from>to 판정은 날짜가 아니라 시각(LocalTime)만 비교한다(주식 한정).
		// 날짜만 보면 from(07-22)이 to(07-23)보다 이르지만, 시각은 09:01 > 09:00이므로 400이어야 한다.
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
		// 날짜만 보면 from(07-23)이 to(07-22)보다 늦지만, 시각은 09:00 <= 09:01이므로 통과해야 한다(날짜 성분은 무시).
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
		// 회귀 확인(MKT-008): volume이 long→BigDecimal로 넓어진 뒤에도 주식 정수 거래량 값은 그대로다.
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

	// --- 주식 집계(1d·1w·1M, 이슈 #143): from>to 판정은 날짜 성분만 비교하고 시각 성분은 무시한다 ---

	@Test
	void getCandlesRejectsStockAggregatedFromAfterToByDateEvenWhenFromTimeIsEarlier() {
		// 1m의 시각(LocalTime) 전용 비교 로직이 집계 interval에 잘못 재사용되면, 09:00 < 10:00이므로 통과해버린다.
		// 날짜 기준으로는 from(07-28)이 to(07-27)보다 늦으므로 거부되어야 한다.
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
		// 같은 날짜(07-27)이면 시각 성분(from 23:59 > to 00:00)과 무관하게 통과해야 한다 — 집계 interval은 날짜만 본다.
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
		// "1D"는 spec상 "1d"의 대소문자 변형으로 여전히 거부되어야 한다(대소문자 미정규화).
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

	// --- 코인(MKT-008, 이슈 #20): 기존 "코인이면 400" 거부 제거 + CryptoCandleProvider 위임 ---

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
		// 코인 volume은 소수 수량이므로 잘리지 않고 그대로 전달돼야 한다.
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
		// 코인은 날짜를 포함한 전체 시각(LocalDateTime)으로 from>to를 비교한다(주식은 LocalTime만).
		// from이 시각만 보면 to보다 늦어 보이지만(20:00 > 08:00), 날짜가 하루 이르므로 전체 시각으로는 from < to다 — 통과해야 한다.
		// 주식의 LocalTime 전용 비교 로직이 코인에 잘못 재사용되면 이 케이스가 거짓으로 400이 된다.
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
		// from이 시각만 보면 to보다 일러 보이지만(08:00 < 20:00), 날짜가 하루 늦으므로 전체 시각으로는 from > to다 — 거부해야 한다.
		// 주식의 LocalTime 전용 비교 로직이 코인에 잘못 재사용되면 이 케이스가 거짓으로 통과된다.
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

	// --- 코인 캔들 경로와 현재가(PriceStore/PriceQueryService) 경로의 독립성 ---

	@Test
	void getCandlesForCryptoDoesNotTouchAnyPriceRelatedComponent() {
		// CandleQueryService는 PriceStore·PriceQueryService에 대한 의존성 자체가 없다 — 코인 캔들 조회가
		// 현재가 조회 경로(Redis)에 전혀 관여하지 않음을 provider 상호작용만으로 고정한다.
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles(any(), any(), any(), any())).thenReturn(List.of());

		service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null);

		verifyNoInteractions(stockPriceProvider);
	}

	@Test
	void cryptoCandleProviderFailureDoesNotPreventFutureStockCandleQueries() {
		// 빗썸 캔들 Provider가 실패해도(예: 502) 같은 CandleQueryService 인스턴스로 이어지는 주식 캔들 조회는 영향받지 않는다.
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
		// 회귀 고정: 이슈 #17에서 추가된 "코인이면 400 VALIDATION_ERROR" 거부가 이슈 #20에서 제거됐다 —
		// 유효한 코인 요청은 더 이상 어떤 경우에도 즉시 VALIDATION_ERROR가 되지 않는다.
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		when(cryptoCandleProvider.getCandles(any(), any(), any(), any())).thenReturn(List.of());

		assertThat(service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", null, null, null)).isNotNull();
		verify(stockPriceProvider, never()).getCandles(any(), any(), any(), any());
	}

	// =====================================================================================
	// 커서 페이지네이션(048, CANDLE-PAGE-001~012·025·026) — plan §7·§12-1
	// =====================================================================================

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
		// 검증 순서(CANDLE-PAGE-010): interval(400)이 커서보다 먼저 판정되므로 잘못된 커서와 무관하게 여전히 400이다.
		assertThatThrownBy(
			() -> service.getCandles(STOCK_INSTRUMENT_ID, "5m", null, null, "also-not-a-cursor"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentRepository);
	}

	@Test
	void getCandlesThrowsNotFoundForMissingInstrumentEvenWithInvalidCursorFormat() {
		// 검증 순서(CANDLE-PAGE-010): instrumentId 존재(404)가 커서 형식 검증(400)보다 먼저다.
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
		// cursorApplies 판정: 코인은 커서가 있으면 항상 적용된다. cursor+to가 함께 오면 to는 무시되고
		// cursor-1분이 상한이 된다(CANDLE-PAGE-012).
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
		// cursorApplies 판정: 집계봉(1d·1w·1M)도 커서가 있으면 항상 적용된다(주식·코인 공통, CANDLE-PAGE-024).
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
		// cursorApplies 판정: 주식 1m은 제외된다(CANDLE-PAGE-025·026, plan §10). 커서가 형식은 유효해도
		// to는 원래 값(여기서는 null) 그대로 provider에 전달돼야 한다 — 봉 조회 자체가 한 글자도 바뀌지 않는다.
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		when(stockPriceProvider.getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null))
			.thenReturn(List.of());

		service.getCandles(
			STOCK_INSTRUMENT_ID, "1m", null, null, LocalDateTime.of(2026, 7, 27, 9, 0).toString());

		verify(stockPriceProvider).getCandles(STOCK_INSTRUMENT_ID, CandleInterval.ONE_MINUTE, null, null);
	}

	@Test
	void getCandlesSetsHasNextTrueAndNextCursorToOldestCandleWhenContentIsFullPage() {
		// hasNext/nextCursor 규칙: content.size()==200 → hasNext=true, nextCursor=content.get(0).sourceTime() 인코딩.
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
		// hasNext/nextCursor 규칙: 200개 미만이면 hasNext=false·nextCursor=null.
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
		// CANDLE-PAGE-026: 주식 1m은 200개가 꽉 차도 hasNext=false·nextCursor=null이 강제된다(유일한 예외).
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
		// tasks.md 명시 케이스: 주식 1m + 커서 → 200, hasNext=false (WebMvc 계약과 동일한 서비스 레벨 확인).
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
		// D-1(plan §5): cursorApplies && from이 정규화된 상한(cursor-1분)보다 뒤면 provider를 부르지 않고
		// 빈 봉투를 즉시 반환한다(400이 아니라 정상 200).
		when(instrumentRepository.findById(CRYPTO_INSTRUMENT_ID)).thenReturn(Optional.of(cryptoInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 30, 9, 0);
		// effectiveTo = cursor - 1분 = 08:59. from = 09:00 > 08:59 → 하한 역전.
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 9, 0);

		CandleListResponse response = service.getCandles(CRYPTO_INSTRUMENT_ID, "1m", from, null, cursor.toString());

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		verify(cryptoCandleProvider, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	void getCandlesReturnsEmptyEnvelopeWithoutCallingStockProviderWhenFromIsAfterNormalizedCursorUpperBoundForAggregated() {
		// D-1이 주식 집계 경로(날짜 성분 비교)에서도 동일하게 동작하는지 확인한다.
		when(instrumentRepository.findById(STOCK_INSTRUMENT_ID)).thenReturn(Optional.of(stockInstrument()));
		LocalDateTime cursor = LocalDateTime.of(2026, 7, 28, 0, 0);
		// effectiveTo = cursor - 1분 = 2026-07-27T23:59 (날짜 2026-07-27). from 날짜(2026-07-28)가 더 늦다 → 역전.
		LocalDateTime from = LocalDateTime.of(2026, 7, 28, 0, 0);

		CandleListResponse response = service.getCandles(STOCK_INSTRUMENT_ID, "1d", from, null, cursor.toString());

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		verify(stockPriceProvider, never()).getCandles(any(), any(), any(), any());
	}

	@Test
	void getCandlesDoesNotEarlyReturnWhenFromEqualsNormalizedCursorUpperBound() {
		// from == effectiveTo(cursor-1분)는 역전이 아니다(isAfter는 초과만 판정) — provider가 정상 호출돼야 한다.
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
