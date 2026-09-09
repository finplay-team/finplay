package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

class StockReplayServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Long INSTRUMENT_ID = 1L;
	private static final LocalTime END_OF_DAY = LocalTime.MAX.withNano(0);
	private static final LocalDate WEEKDAY = LocalDate.of(2026, 7, 27);
	private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 1);
	private static final LocalDate HOLIDAY = LocalDate.of(2026, 1, 1);

	private final StockReplaySessionRepository stockReplaySessionRepository = mock(StockReplaySessionRepository.class);
	private final StockCandleRepository stockCandleRepository = mock(StockCandleRepository.class);
	private final StockDailyCandleRepository stockDailyCandleRepository = mock(StockDailyCandleRepository.class);

	private static Clock fixedClock(LocalDate date, LocalTime time) {
		return Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
	}

	private StockReplayService service(Clock clock) {
		return new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, stockDailyCandleRepository, clock,
			new BusinessDayCalendar());
	}

	private static StockReplaySession readySession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		return StockReplaySession.ready(serviceDate, sourceTradingDate,
			LocalDateTime.of(serviceDate, LocalTime.of(8, 30)), LocalDateTime.of(serviceDate, LocalTime.of(8, 0)));
	}

	private static StockCandle candle(LocalTime candleTime, BigDecimal open, BigDecimal close) {
		Instrument instrument = Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 10000L, true,
			LocalDateTime.now());
		return StockCandle.create(
			instrument, WEEKDAY, candleTime, open, open, open, close, 100L, "KRX", LocalDateTime.now());
	}

	private static StockCandle candle(
		LocalDate tradingDate, LocalTime candleTime, BigDecimal open, BigDecimal high, BigDecimal low,
		BigDecimal close, long volume) {
		Instrument instrument = Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 10000L, true,
			LocalDateTime.now());
		return StockCandle.create(
			instrument, tradingDate, candleTime, open, high, low, close, volume, "KRX", LocalDateTime.now());
	}

	private static BigDecimal bd(long value) {
		return BigDecimal.valueOf(value);
	}

	@Test
	void getMarketStatusReturnsClosedWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		StockMarketStatus status = service.getMarketStatus();

		assertThat(status).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsClosedWhenPreparationStatusIsPreparing() {
		StockReplaySession preparing = StockReplaySession.preparing(WEEKDAY, null,
			LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)));
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(preparing));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsClosedWhenPreparationStatusIsFailed() {
		StockReplaySession failed = StockReplaySession.failed(
			WEEKDAY, null, LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)), "NO_DATA",
			LocalDateTime.of(WEEKDAY, LocalTime.of(7, 0)));
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(failed));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsClosedWhenReadyAndBeforeMarketOpen() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 59, 59)));

		assertThat(service.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsOpenWhenReadyAndWithinTradingHours() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));

		assertThat(service(fixedClock(WEEKDAY, LocalTime.of(9, 0))).getMarketStatus())
			.isEqualTo(StockMarketStatus.OPEN);
		assertThat(service(fixedClock(WEEKDAY, LocalTime.of(12, 30))).getMarketStatus())
			.isEqualTo(StockMarketStatus.OPEN);
		assertThat(service(fixedClock(WEEKDAY, LocalTime.of(15, 29, 59))).getMarketStatus())
			.isEqualTo(StockMarketStatus.OPEN);
	}

	@Test
	void getMarketStatusReturnsClosedWhenReadyAndAfterMarketClose() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));

		assertThat(service(fixedClock(WEEKDAY, LocalTime.of(15, 30))).getMarketStatus())
			.isEqualTo(StockMarketStatus.CLOSED);
		assertThat(service(fixedClock(WEEKDAY, LocalTime.of(18, 0))).getMarketStatus())
			.isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsClosedWhenReadyAndWeekend() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY))
			.thenReturn(Optional.of(readySession(SATURDAY, SATURDAY)));
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(10, 0)));

		assertThat(service.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getMarketStatusReturnsClosedWhenReadyAndHoliday() {
		when(stockReplaySessionRepository.findByServiceDate(HOLIDAY))
			.thenReturn(Optional.of(readySession(HOLIDAY, HOLIDAY)));
		StockReplayService service = service(fixedClock(HOLIDAY, LocalTime.of(10, 0)));

		assertThat(service.getMarketStatus()).isEqualTo(StockMarketStatus.CLOSED);
	}

	@Test
	void getCurrentPriceReturnsFirstCandleOpenDuringFirstCandleWindow() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle firstCandle = candle(LocalTime.of(9, 0), BigDecimal.valueOf(1000), BigDecimal.valueOf(1010));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(INSTRUMENT_ID, WEEKDAY))
			.thenReturn(Optional.of(firstCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.sessionReady()).isTrue();
		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(dto.sourceTradingDate()).isEqualTo(WEEKDAY);
		assertThat(dto.price()).isEqualTo(BigDecimal.valueOf(1000));
		assertThat(dto.sourceTime()).isEqualTo(LocalDateTime.of(WEEKDAY, LocalTime.of(9, 0)));
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 0));
	}

	@Test
	void getCurrentPriceReturnsLastClosedCandleCloseAfterFirstCandleWindow() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle closedCandle = candle(LocalTime.of(9, 1), BigDecimal.valueOf(1010), BigDecimal.valueOf(1020));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.of(closedCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.price()).isEqualTo(BigDecimal.valueOf(1020));
		assertThat(dto.sourceTime()).isEqualTo(LocalDateTime.of(WEEKDAY, LocalTime.of(9, 1)));
		verify(stockCandleRepository)
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1));
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 2));
	}

	@Test
	void getCurrentPriceReturnsUnavailableBeforeMarketOpenWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 59)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.sessionReady()).isTrue();
		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.price()).isNull();
		assertThat(dto.sourceTime()).isNull();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getCurrentPriceKeepsLastClosedCandleCloseAfterMarketClose() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(2000), BigDecimal.valueOf(2050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(15, 59)))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(16, 0)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sessionReady()).isTrue();
		assertThat(dto.price()).isEqualTo(BigDecimal.valueOf(2050));
		assertThat(dto.sourceTime()).isEqualTo(LocalDateTime.of(WEEKDAY, LocalTime.of(15, 29)));
	}

	@Test
	void getCurrentPriceReturnsSessionNotReadyDtoWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.sessionReady()).isFalse();
		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isNull();
		assertThat(dto.price()).isNull();
		assertThat(dto.sourceTime()).isNull();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getCurrentPriceReturnsPriceUnavailableWhenCandleMissingForInstrument() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 0)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.sessionReady()).isTrue();
		assertThat(dto.sourceTradingDate()).isEqualTo(WEEKDAY);
		assertThat(dto.price()).isNull();
		assertThat(dto.sourceTime()).isNull();
		assertThat(dto.isPriceAvailable()).isFalse();
	}

	@Test
	void getCurrentPriceReturnsTheReadySessionUsedToComputeThePrice() {
		StockReplaySession session = readySession(WEEKDAY, WEEKDAY);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(session));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.replaySession()).isSameAs(session);
	}

	@Test
	void getCurrentPriceReturnsNullReplaySessionWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.replaySession()).isNull();
	}

	@Test
	void getCurrentPricesComputesReadySessionAndMarketStatusOnlyOnceForMultipleInstruments() {
		Long secondInstrumentId = 2L;
		Long thirdInstrumentId = 3L;
		StockReplaySession session = readySession(WEEKDAY, WEEKDAY);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(session));
		StockCandle closedCandle = candle(LocalTime.of(9, 1), BigDecimal.valueOf(1010), BigDecimal.valueOf(1020));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			any(), eq(WEEKDAY), eq(LocalTime.of(9, 1))))
			.thenReturn(Optional.of(closedCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockReplayPriceDto> results = service
			.getCurrentPrices(List.of(INSTRUMENT_ID, secondInstrumentId, thirdInstrumentId));

		assertThat(results).hasSize(3);
		assertThat(results).allSatisfy(dto -> {
			assertThat(dto.sessionReady()).isTrue();
			assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
			assertThat(dto.price()).isEqualTo(BigDecimal.valueOf(1020));
			assertThat(dto.replaySession()).isSameAs(session);
		});
		verify(stockReplaySessionRepository, times(1)).findByServiceDate(WEEKDAY);
		verify(stockCandleRepository, times(3))
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				any(), eq(WEEKDAY), eq(LocalTime.of(9, 1)));
		verify(stockCandleRepository)
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1));
		verify(stockCandleRepository)
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				secondInstrumentId, WEEKDAY, LocalTime.of(9, 1));
		verify(stockCandleRepository)
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				thirdInstrumentId, WEEKDAY, LocalTime.of(9, 1));
	}

	@Test
	void getCurrentPricesReturnsResultsInSameOrderAsRequestedInstrumentIds() {
		Long secondInstrumentId = 2L;
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle candleForFirst = candle(LocalTime.of(9, 1), BigDecimal.valueOf(100), BigDecimal.valueOf(110));
		StockCandle candleForSecond = candle(LocalTime.of(9, 1), BigDecimal.valueOf(200), BigDecimal.valueOf(220));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.of(candleForFirst));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			secondInstrumentId, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.of(candleForSecond));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockReplayPriceDto> results = service.getCurrentPrices(List.of(INSTRUMENT_ID, secondInstrumentId));

		assertThat(results.get(0).price()).isEqualTo(BigDecimal.valueOf(110));
		assertThat(results.get(1).price()).isEqualTo(BigDecimal.valueOf(220));
	}

	@Test
	void getCurrentPricesReturnsSessionNotReadyForAllInstrumentsWithoutQueryingCandlesWhenNoReadySession() {
		Long secondInstrumentId = 2L;
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockReplayPriceDto> results = service.getCurrentPrices(List.of(INSTRUMENT_ID, secondInstrumentId));

		assertThat(results).hasSize(2);
		assertThat(results).allSatisfy(dto -> {
			assertThat(dto.sessionReady()).isFalse();
			assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
			assertThat(dto.price()).isNull();
			assertThat(dto.replaySession()).isNull();
		});
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getCurrentPricesHandlesMixedCandleAvailabilityAcrossInstruments() {
		Long missingCandleInstrumentId = 2L;
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle closedCandle = candle(LocalTime.of(9, 1), BigDecimal.valueOf(1010), BigDecimal.valueOf(1020));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.of(closedCandle));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			missingCandleInstrumentId, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockReplayPriceDto> results = service
			.getCurrentPrices(List.of(INSTRUMENT_ID, missingCandleInstrumentId));

		assertThat(results.get(0).isPriceAvailable()).isTrue();
		assertThat(results.get(0).price()).isEqualTo(BigDecimal.valueOf(1020));
		assertThat(results.get(1).isPriceAvailable()).isFalse();
		assertThat(results.get(1).sessionReady()).isTrue();
		assertThat(results.get(1).sourceTradingDate()).isEqualTo(WEEKDAY);
	}

	@Test
	void getCurrentPriceDelegatesToGetCurrentPricesAndMatchesBatchResultForSameInstrument() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle closedCandle = candle(LocalTime.of(9, 1), BigDecimal.valueOf(1010), BigDecimal.valueOf(1020));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.of(closedCandle));

		StockReplayPriceDto single = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)))
			.getCurrentPrice(INSTRUMENT_ID);
		StockReplayPriceDto batchFirst = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)))
			.getCurrentPrices(List.of(INSTRUMENT_ID))
			.get(0);

		assertThat(single).isEqualTo(batchFirst);
	}

	private static final LocalDate FALLBACK_TRADING_DATE = LocalDate.of(2026, 7, 24);
	private static final LocalDate FRIDAY_BEFORE_SATURDAY = LocalDate.of(2026, 7, 31);

	private static StockReplaySession fallbackSession() {
		return readySession(FALLBACK_TRADING_DATE, FALLBACK_TRADING_DATE);
	}

	@Test
	void getCurrentPricesFallsBackToLastReplayedTradingDayOnWeekendWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(readySession(FRIDAY_BEFORE_SATURDAY, FRIDAY_BEFORE_SATURDAY)));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(2000), BigDecimal.valueOf(2050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			any(), eq(FRIDAY_BEFORE_SATURDAY)))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(15, 0)));

		List<StockReplayPriceDto> results = service.getCurrentPrices(List.of(INSTRUMENT_ID, 2L, 3L));

		assertThat(results).allSatisfy(dto -> {
			assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
			assertThat(dto.sourceTradingDate()).isEqualTo(FRIDAY_BEFORE_SATURDAY);
			assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(2050));
			assertThat(dto.sourceTime()).isEqualTo(LocalDateTime.of(FRIDAY_BEFORE_SATURDAY, LocalTime.of(15, 29)));
			assertThat(dto.sessionReady()).isFalse();
			assertThat(dto.replaySession()).isNull();
		});
		verify(stockReplaySessionRepository, times(1))
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY);
	}

	@Test
	void getCurrentPricesFallsBackOnHolidayWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(HOLIDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(HOLIDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(3000), BigDecimal.valueOf(3050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(HOLIDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(3050));
	}

	@Test
	void getCurrentPricesFallsBackOnWeekdayBeforeSessionRowExistsAtZeroThirty() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(4000), BigDecimal.valueOf(4050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(0, 30)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(dto.sourceTradingDate()).isNotEqualTo(WEEKDAY);
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(4050));
	}

	@Test
	void getCurrentPricesFallsBackOnWeekdayAtZeroEightFiftyWithoutLeakingTodaySessionSourceTradingDate() {
		StockReplaySession todaySession = readySession(WEEKDAY, WEEKDAY);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(todaySession));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(5000), BigDecimal.valueOf(5050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 50)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(dto.sourceTradingDate()).isNotEqualTo(todaySession.getSourceTradingDate());
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(5050));
		assertThat(dto.sessionReady()).isFalse();
		assertThat(dto.replaySession()).isNull();
	}

	@Test
	void getCurrentPricesDoesNotFallBackWhenMarketOpenAndCandleMissingForOneInstrument() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1)))
			.thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle poisonedFallbackCandle = candle(LocalTime.of(15, 29), bd(9999), bd(9999));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(poisonedFallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 0)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(dto.isPriceAvailable()).isFalse();
		assertThat(dto.price()).isNull();
		assertThat(dto.sessionReady()).isTrue();
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(any(), any());
	}

	@Test
	void getCurrentPricesFallsBackWhenTodaySessionIsFailed() {
		StockReplaySession failed = StockReplaySession.failed(
			WEEKDAY, null, LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)), "NO_DATA",
			LocalDateTime.of(WEEKDAY, LocalTime.of(7, 0)));
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(failed));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(6000), BigDecimal.valueOf(6050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(6050));
	}

	@Test
	void getCurrentPricesFallsBackWhenTodaySessionIsPreparing() {
		StockReplaySession preparing = StockReplaySession.preparing(WEEKDAY, null,
			LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)));
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(preparing));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle lastCandle = candle(LocalTime.of(15, 29), BigDecimal.valueOf(7000), BigDecimal.valueOf(7050));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sourceTradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(7050));
	}

	@Test
	void getCurrentPricesStaysUnavailableWhenFallbackSessionSourceTradingDateHasNoCandle() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(10, 0)));

		StockReplayPriceDto dto = service.getCurrentPrices(List.of(INSTRUMENT_ID)).get(0);

		assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
		assertThat(dto.sessionReady()).isFalse();
		assertThat(dto.sourceTradingDate()).isNull();
		assertThat(dto.price()).isNull();
		assertThat(dto.isPriceAvailable()).isFalse();
		assertThat(dto.replaySession()).isNull();
	}

	@Test
	void getRevealedCandlesReturnsEmptyListWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesReturnsEmptyListBeforeMarketOpenWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 59)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesReturnsEmptyListDuringFirstCandleWindowWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesRevealsFirstCandleExactlyAtOneMinuteBoundary() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle firstCandle = candle(LocalTime.of(9, 0), BigDecimal.valueOf(1000), BigDecimal.valueOf(1010));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 0)))
			.thenReturn(List.of(firstCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 1, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).candleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(candles.get(0).tradingDate()).isEqualTo(WEEKDAY);
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(any(), any());
	}

	@Test
	void getRevealedCandlesReturnsCandlesUpToLastClosedMinuteAfterFirstCandleWindowWhenFromToOmitted() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle firstCandle = candle(LocalTime.of(9, 0), BigDecimal.valueOf(1000), BigDecimal.valueOf(1010));
		StockCandle secondCandle = candle(LocalTime.of(9, 1), BigDecimal.valueOf(1010), BigDecimal.valueOf(1020));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 1)))
			.thenReturn(List.of(firstCandle, secondCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(StockCandleDto::candleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 1));
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(INSTRUMENT_ID, WEEKDAY);
	}

	@Test
	void getRevealedCandlesClipsRequestedToBeyondCutoffSoUnclosedCandleIsNeverExposed() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime requestedTo = LocalDateTime.of(WEEKDAY, LocalTime.of(9, 10));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 4)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		service.getRevealedCandles(INSTRUMENT_ID, null, requestedTo);

		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 4));
		verify(stockCandleRepository, never()).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 10));
	}

	@Test
	void getRevealedCandlesUsesRequestedToWhenItIsBeforeTheCutoff() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime requestedTo = LocalDateTime.of(WEEKDAY, LocalTime.of(9, 2));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 2)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		service.getRevealedCandles(INSTRUMENT_ID, null, requestedTo);

		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 2));
	}

	@Test
	void getRevealedCandlesReturnsEmptyListWhenRequestedFromIsAfterClippedRangeEndWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime requestedFrom = LocalDateTime.of(WEEKDAY, LocalTime.of(9, 10));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, requestedFrom, null);

		assertThat(candles).isEmpty();
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(any(), any(), any(), any());
	}

	@Test
	void getRevealedCandlesIgnoresDateComponentOfFromAndAlwaysScopesToSourceTradingDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime fromWithUnrelatedDate = LocalDateTime.of(LocalDate.of(2099, 1, 1), LocalTime.of(9, 1));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1), LocalTime.of(9, 4)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		service.getRevealedCandles(INSTRUMENT_ID, fromWithUnrelatedDate, null);

		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1), LocalTime.of(9, 4));
	}

	@Test
	void getRevealedAggregatedCandlesReturnsEmptyListWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, null, null))
			.isEmpty();
		assertThat(service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_WEEK, null, null))
			.isEmpty();
		assertThat(service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_MONTH, null, null))
			.isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedAggregatedCandlesReturnsEmptyListWithoutQueryingCandlesWhenFromDateIsAfterToDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, WEEKDAY, WEEKDAY.minusDays(1));

		assertThat(result).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedAggregatedCandlesOmitsReplayDayBucketBeforeOneMinuteCutoff() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate priorTradingDate = WEEKDAY.minusDays(3);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, priorTradingDate, WEEKDAY.minusDays(1)))
			.thenReturn(List.of(
				candle(priorTradingDate, LocalTime.of(9, 0), bd(1000), bd(1005), bd(995), bd(1002), 10)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, priorTradingDate, WEEKDAY);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(priorTradingDate);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				any(), eq(WEEKDAY), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesRevealsReplayDayFirstMinuteExactlyAtOneMinuteBoundary() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 0)))
			.thenReturn(List.of(candle(WEEKDAY, LocalTime.of(9, 0), bd(1000), bd(1010), bd(995), bd(1005), 10)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 1, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, WEEKDAY, WEEKDAY);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).close()).isEqualByComparingTo(bd(1005));
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(any(), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesBuildsInProgressReplayDayBucketFromOnlyCutoffRevealedMinutesAfterOneMinuteCutoff() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 4)))
			.thenReturn(List.of(
				candle(WEEKDAY, LocalTime.of(9, 0), bd(1000), bd(1010), bd(995), bd(1005), 10),
				candle(WEEKDAY, LocalTime.of(9, 1), bd(1005), bd(1020), bd(1000), bd(1015), 20)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, WEEKDAY, WEEKDAY);

		assertThat(result).hasSize(1);
		StockCandleDto bucket = result.get(0);
		assertThat(bucket.tradingDate()).isEqualTo(WEEKDAY);
		assertThat(bucket.open()).isEqualByComparingTo(bd(1000));
		assertThat(bucket.high()).isEqualByComparingTo(bd(1020));
		assertThat(bucket.low()).isEqualByComparingTo(bd(995));
		assertThat(bucket.close()).isEqualByComparingTo(bd(1015));
		assertThat(bucket.volume()).isEqualTo(30L);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), eq(LocalTime.of(9, 5)));
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(any(), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesNeverQueriesTradingDatesAfterSourceTradingDateEvenWhenToDateIsFarInFuture() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate farFutureToDate = WEEKDAY.plusDays(30);
		LocalDate requestedFrom = WEEKDAY.minusDays(10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(requestedFrom), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), any()))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, requestedFrom, farFutureToDate);

		ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(requestedFrom), toCaptor.capture());
		assertThat(toCaptor.getValue()).isEqualTo(WEEKDAY.minusDays(1));
		assertThat(toCaptor.getValue()).isBefore(WEEKDAY);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(farFutureToDate), any(), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(requestedFrom), eq(farFutureToDate));
	}

	@Test
	void getRevealedAggregatedCandlesKeepsOnlyLatestTwoHundredBucketsWhenMoreThanTwoHundredExist() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate fromDate = WEEKDAY.minusDays(300);
		LocalDate toDate = WEEKDAY.minusDays(50);
		int totalDays = 205;
		List<StockCandle> minuteCandles = new ArrayList<>();
		for (int i = 0; i < totalDays; i++) {
			LocalDate tradingDate = fromDate.plusDays(i);
			minuteCandles.add(candle(
				tradingDate, LocalTime.of(9, 0), bd(1000 + i), bd(1000 + i), bd(1000 + i), bd(1000 + i), 1));
		}
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, fromDate, toDate))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, fromDate, toDate);

		assertThat(result).hasSize(200);
		assertThat(result.get(0).tradingDate()).isEqualTo(fromDate.plusDays(5));
		assertThat(result.get(199).tradingDate()).isEqualTo(fromDate.plusDays(204));
	}

	@Test
	void getRevealedAggregatedCandlesAppliesFourHundredDayLookbackFloorForDailyIntervalWhenFromOmitted() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, null, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(WEEKDAY.minusDays(1)));
		assertThat(fromCaptor.getValue()).isEqualTo(WEEKDAY.minusDays(400));
	}

	@Test
	void getRevealedAggregatedCandlesAppliesTwoHundredWeekLookbackFloorForWeeklyIntervalWhenFromOmitted() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_WEEK, null, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(WEEKDAY.minusDays(1)));
		assertThat(fromCaptor.getValue()).isEqualTo(WEEKDAY.minusWeeks(200));
	}

	@Test
	void getRevealedAggregatedCandlesAppliesTwoHundredMonthLookbackFloorForMonthlyIntervalWhenFromOmitted() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_MONTH, null, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(WEEKDAY.minusDays(1)));
		assertThat(fromCaptor.getValue()).isEqualTo(WEEKDAY.minusMonths(200));
	}

	@Test
	void getRevealedAggregatedCandlesUsesExplicitFromDateInsteadOfLookbackFloorWhenProvided() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate explicitFrom = WEEKDAY.minusDays(5);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, explicitFrom, WEEKDAY.minusDays(1)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, explicitFrom, null);

		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, explicitFrom, WEEKDAY.minusDays(1));
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				INSTRUMENT_ID, WEEKDAY.minusDays(400), WEEKDAY.minusDays(1));
	}

	@Test
	void getRevealedAggregatedCandlesExcludesLeadingPartialWeekBucketButIncludesNextCompleteWeekWhenFromFallsMidWeek() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate midWeekFrom = LocalDate.of(2026, 7, 8);
		LocalDate completeWeekEnd = LocalDate.of(2026, 7, 17);
		List<StockCandle> minuteCandles = List.of(
			candle(LocalDate.of(2026, 7, 8), LocalTime.of(9, 0), bd(1000), bd(1010), bd(995), bd(1005), 10),
			candle(LocalDate.of(2026, 7, 9), LocalTime.of(9, 0), bd(1005), bd(1015), bd(1000), bd(1010), 10),
			candle(LocalDate.of(2026, 7, 10), LocalTime.of(9, 0), bd(1010), bd(1020), bd(1005), bd(1015), 10),
			candle(LocalDate.of(2026, 7, 13), LocalTime.of(9, 0), bd(1020), bd(1030), bd(1015), bd(1025), 10),
			candle(LocalDate.of(2026, 7, 14), LocalTime.of(9, 0), bd(1025), bd(1035), bd(1020), bd(1030), 10),
			candle(LocalDate.of(2026, 7, 15), LocalTime.of(9, 0), bd(1030), bd(1040), bd(1025), bd(1035), 10),
			candle(LocalDate.of(2026, 7, 16), LocalTime.of(9, 0), bd(1035), bd(1045), bd(1030), bd(1040), 10),
			candle(LocalDate.of(2026, 7, 17), LocalTime.of(9, 0), bd(1040), bd(1050), bd(1035), bd(1045), 10));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, midWeekFrom, completeWeekEnd))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_WEEK, midWeekFrom, completeWeekEnd);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2026, 7, 13));
	}

	@Test
	void getRevealedAggregatedCandlesIncludesWeekBucketWhenFromFallsExactlyOnBucketMonday() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate mondayFrom = LocalDate.of(2026, 7, 13);
		LocalDate weekEnd = LocalDate.of(2026, 7, 17);
		List<StockCandle> minuteCandles = List.of(
			candle(LocalDate.of(2026, 7, 13), LocalTime.of(9, 0), bd(1020), bd(1030), bd(1015), bd(1025), 10),
			candle(LocalDate.of(2026, 7, 17), LocalTime.of(9, 0), bd(1040), bd(1050), bd(1035), bd(1045), 10));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, mondayFrom, weekEnd))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_WEEK, mondayFrom, weekEnd);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(mondayFrom);
	}

	@Test
	void getRevealedAggregatedCandlesExcludesLeadingPartialMonthBucketWhenFromFallsMidMonth() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate midMonthFrom = LocalDate.of(2026, 5, 15);
		LocalDate completeMonthEnd = LocalDate.of(2026, 6, 30);
		List<StockCandle> minuteCandles = List.of(
			candle(LocalDate.of(2026, 5, 15), LocalTime.of(9, 0), bd(1000), bd(1010), bd(995), bd(1005), 10),
			candle(LocalDate.of(2026, 5, 29), LocalTime.of(9, 0), bd(1005), bd(1015), bd(1000), bd(1010), 10),
			candle(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), bd(1020), bd(1030), bd(1015), bd(1025), 10),
			candle(LocalDate.of(2026, 6, 30), LocalTime.of(9, 0), bd(1040), bd(1050), bd(1035), bd(1045), 10));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, midMonthFrom, completeMonthEnd))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_MONTH, midMonthFrom, completeMonthEnd);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(LocalDate.of(2026, 6, 1));
	}

	private static List<LocalDate> consecutiveDaysDescending(LocalDate mostRecentInclusive, int count) {
		List<LocalDate> dates = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			dates.add(mostRecentInclusive.minusDays(i));
		}
		return dates;
	}

	@Test
	void getRevealedAggregatedCandlesNarrowsDailyLookbackFloorToTheActualTwoHundredthBucketStartDateWhenDataIsDense() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		LocalDate wideLookbackFloor = WEEKDAY.minusDays(400);
		List<LocalDate> denseRecentDates = consecutiveDaysDescending(pastEnd, 205);
		when(stockCandleRepository.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, wideLookbackFloor, pastEnd, PageRequest.of(0, 200)))
			.thenReturn(denseRecentDates);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(pastEnd)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, null, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(pastEnd));
		assertThat(fromCaptor.getValue()).isEqualTo(denseRecentDates.get(199));
		assertThat(fromCaptor.getValue()).isAfter(wideLookbackFloor);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				INSTRUMENT_ID, wideLookbackFloor, pastEnd);
	}

	@Test
	void getRevealedAggregatedCandlesNarrowsExplicitWideFromDateWhenActualDataIsShallow() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate explicitFrom = WEEKDAY.minusDays(1000);
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		List<LocalDate> shallowDates = consecutiveDaysDescending(pastEnd, 5);
		when(stockCandleRepository.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, explicitFrom, pastEnd, PageRequest.of(0, 200)))
			.thenReturn(shallowDates);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(pastEnd)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, explicitFrom, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(pastEnd));
		assertThat(fromCaptor.getValue()).isEqualTo(shallowDates.get(4));
		assertThat(fromCaptor.getValue()).isAfter(explicitFrom);
	}

	@Test
	void getRevealedAggregatedCandlesQueriesDistinctTradingDatesWithIntervalSpecificFetchLimitForWeeklyAndMonthly() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(pastEnd)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_WEEK, null, null);
		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_MONTH, null, null);

		verify(stockCandleRepository).findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, WEEKDAY.minusWeeks(200), pastEnd, PageRequest.of(0, 1400));
		verify(stockCandleRepository).findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, WEEKDAY.minusMonths(200), pastEnd, PageRequest.of(0, 6200));
	}

	@Test
	void getRevealedAggregatedCandlesNarrowsWeeklyRangeStartToTheBucketStartDateNotTheFirstEncounteredTradingDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate explicitFrom = WEEKDAY.minusYears(10);
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		int weekCount = 200;
		LocalDate mostRecentFriday = LocalDate.of(2026, 7, 24);
		List<LocalDate> recentTradingDates = new ArrayList<>();
		LocalDate friday = mostRecentFriday;
		for (int i = 0; i < weekCount; i++) {
			recentTradingDates.add(friday);
			recentTradingDates.add(friday.minusDays(4));
			friday = friday.minusWeeks(1);
		}
		LocalDate oldestBucketFriday = mostRecentFriday.minusWeeks(weekCount - 1);
		LocalDate oldestBucketMonday = oldestBucketFriday.minusDays(4);
		when(stockCandleRepository.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, explicitFrom, pastEnd, PageRequest.of(0, 1400)))
			.thenReturn(recentTradingDates);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(pastEnd)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_WEEK, explicitFrom, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(pastEnd));
		assertThat(fromCaptor.getValue()).isEqualTo(oldestBucketMonday);
		assertThat(fromCaptor.getValue()).isNotEqualTo(oldestBucketFriday);
	}

	@Test
	void getRevealedAggregatedCandlesIncludesMonthBucketWhenFromFallsExactlyOnFirstOfMonth() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate firstOfMonthFrom = LocalDate.of(2026, 6, 1);
		LocalDate monthEnd = LocalDate.of(2026, 6, 30);
		List<StockCandle> minuteCandles = List.of(
			candle(LocalDate.of(2026, 6, 1), LocalTime.of(9, 0), bd(1020), bd(1030), bd(1015), bd(1025), 10),
			candle(LocalDate.of(2026, 6, 30), LocalTime.of(9, 0), bd(1040), bd(1050), bd(1035), bd(1045), 10));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, firstOfMonthFrom, monthEnd))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_MONTH, firstOfMonthFrom, monthEnd);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(firstOfMonthFrom);
	}

	@Test
	void getRevealedAggregatedCandlesNarrowRangeStartQueryEndMovesToTheDayBeforeTheCursorDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime cursor = LocalDateTime.of(LocalDate.of(2026, 7, 20), LocalTime.MIDNIGHT);
		LocalDate cursorDerivedToDate = cursor.minusMinutes(1).toLocalDate();
		assertThat(cursorDerivedToDate).isEqualTo(cursor.toLocalDate().minusDays(1));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			any(), any(), eq(cursorDerivedToDate)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, null, cursorDerivedToDate);

		ArgumentCaptor<LocalDate> queryEndCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			eq(INSTRUMENT_ID), any(), queryEndCaptor.capture(), eq(PageRequest.of(0, 200)));
		assertThat(queryEndCaptor.getValue()).isEqualTo(cursorDerivedToDate);
	}

	@Test
	void getRevealedAggregatedCandlesKeepsExactlyTwoHundredBucketsWhenLeadingPartialBucketWouldOtherwiseLeakThePage() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate week0Monday = LocalDate.of(2020, 1, 6);
		LocalDate rangeStart = week0Monday.plusDays(2);
		LocalDate lastWeekMonday = week0Monday.plusWeeks(200);
		LocalDate rangeEnd = lastWeekMonday.plusDays(4);

		List<StockCandle> minuteCandles = new ArrayList<>();
		minuteCandles.add(candle(rangeStart, LocalTime.of(9, 0), bd(1), bd(1), bd(1), bd(1), 1));
		for (int week = 1; week <= 200; week++) {
			LocalDate monday = week0Monday.plusWeeks(week);
			minuteCandles.add(candle(monday, LocalTime.of(9, 0), bd(1000 + week), bd(1000 + week), bd(1000 + week),
				bd(1000 + week), 1));
		}
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, rangeStart, rangeEnd))
			.thenReturn(minuteCandles);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_WEEK, rangeStart, rangeEnd);

		assertThat(result).hasSize(200);
		assertThat(result.get(0).tradingDate()).isEqualTo(week0Monday.plusWeeks(1));
		assertThat(result.get(199).tradingDate()).isEqualTo(lastWeekMonday);
	}

	@Test
	void getRevealedAggregatedCandlesClampsRangeEndToSourceTradingDateWhenCursorDerivedToDateIsAfterIt() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime futureCursor = LocalDateTime.of(WEEKDAY.plusDays(30), LocalTime.MIDNIGHT);
		LocalDate cursorDerivedToDate = futureCursor.minusMinutes(1).toLocalDate();
		LocalDate requestedFrom = WEEKDAY.minusDays(10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(requestedFrom), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), any()))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, requestedFrom, cursorDerivedToDate);

		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(cursorDerivedToDate), any(), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(requestedFrom), eq(cursorDerivedToDate));
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), any());
	}

	@Test
	void getRevealedAggregatedCandlesReturnsEmptyListWhenNoReadySessionRegardlessOfCursorDerivedToDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.empty());
		LocalDateTime cursor = LocalDateTime.of(WEEKDAY.minusDays(5), LocalTime.MIDNIGHT);
		LocalDate cursorDerivedToDate = cursor.minusMinutes(1).toLocalDate();
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, null, cursorDerivedToDate);

		assertThat(result).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedAggregatedCandlesOmitsReplayDayBucketBeforeOneMinuteCutoffRegardlessOfCursorDerivedToDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate priorTradingDate = WEEKDAY.minusDays(3);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, priorTradingDate, WEEKDAY.minusDays(1)))
			.thenReturn(List.of(
				candle(priorTradingDate, LocalTime.of(9, 0), bd(1000), bd(1005), bd(995), bd(1002), 10)));
		LocalDateTime cursor = LocalDateTime.of(WEEKDAY.plusDays(1), LocalTime.MIDNIGHT);
		LocalDate cursorDerivedToDate = cursor.minusMinutes(1).toLocalDate();
		assertThat(cursorDerivedToDate).isEqualTo(WEEKDAY);
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, priorTradingDate, cursorDerivedToDate);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(priorTradingDate);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				any(), eq(WEEKDAY), any(), any());
	}

	@Test
	void getRevealedCandlesFallsBackToLastReplayedTradingDayOnWeekendWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(readySession(FRIDAY_BEFORE_SATURDAY, FRIDAY_BEFORE_SATURDAY)));
		StockCandle firstCandle = candle(FRIDAY_BEFORE_SATURDAY, LocalTime.of(9, 0), bd(2000), bd(2010), bd(1995),
			bd(2005), 10);
		StockCandle lastCandle = candle(FRIDAY_BEFORE_SATURDAY, LocalTime.of(15, 29), bd(2005), bd(2020), bd(2000),
			bd(2015), 20);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FRIDAY_BEFORE_SATURDAY, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(firstCandle, lastCandle));
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(15, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(StockCandleDto::tradingDate)
			.containsOnly(FRIDAY_BEFORE_SATURDAY);
		assertThat(candles).extracting(StockCandleDto::candleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(15, 29));
	}

	@Test
	void getRevealedCandlesFallsBackOnWeekdayBeforeSessionRowExistsAtZeroThirty() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle fallbackCandle = candle(FALLBACK_TRADING_DATE, LocalTime.of(15, 29), bd(3000), bd(3010), bd(2995),
			bd(3005), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(fallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(0, 30)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
	}

	@Test
	void getRevealedCandlesFallsBackOnWeekdayAtZeroEightFiftyWithoutLeakingTodaySessionSourceTradingDate() {
		StockReplaySession todaySession = readySession(WEEKDAY, WEEKDAY);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(todaySession));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle fallbackCandle = candle(FALLBACK_TRADING_DATE, LocalTime.of(15, 29), bd(4000), bd(4010), bd(3995),
			bd(4005), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(fallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 50)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(candles.get(0).tradingDate()).isNotEqualTo(todaySession.getSourceTradingDate());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(WEEKDAY), any(), any());
	}

	@Test
	void getRevealedCandlesDoesNotFallBackDuringFirstCandleWindowEvenWhenFallbackSessionExists() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesReturnsTodayCandlesAfterMarketCloseWithoutFallingBack() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockCandle lastCandle = candle(WEEKDAY, LocalTime.of(15, 29), bd(5000), bd(5010), bd(4995), bd(5005), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(15, 30)))
			.thenReturn(List.of(lastCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(15, 31)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(WEEKDAY);
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
	}

	@Test
	void getRevealedCandlesStaysEmptyWhenFallbackSessionSourceTradingDateHasNoCandle() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
	}

	@Test
	void getRevealedAggregatedCandlesFallsBackToLastReplayedTradingDayOnWeekendWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(SATURDAY)).thenReturn(Optional.empty());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(SATURDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(readySession(FRIDAY_BEFORE_SATURDAY, FRIDAY_BEFORE_SATURDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FRIDAY_BEFORE_SATURDAY, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(
				candle(FRIDAY_BEFORE_SATURDAY, LocalTime.of(9, 0), bd(1000), bd(1010), bd(995), bd(1005), 10),
				candle(FRIDAY_BEFORE_SATURDAY, LocalTime.of(15, 29), bd(1005), bd(1015), bd(1000), bd(1012), 20)));
		StockReplayService service = service(fixedClock(SATURDAY, LocalTime.of(15, 0)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, FRIDAY_BEFORE_SATURDAY, FRIDAY_BEFORE_SATURDAY);

		assertThat(result).hasSize(1);
		StockCandleDto bucket = result.get(0);
		assertThat(bucket.tradingDate()).isEqualTo(FRIDAY_BEFORE_SATURDAY);
		assertThat(bucket.open()).isEqualByComparingTo(bd(1000));
		assertThat(bucket.close()).isEqualByComparingTo(bd(1012));
		assertThat(bucket.volume()).isEqualTo(30L);
	}

	@Test
	void getRevealedAggregatedCandlesFallsBackOnWeekdayAtZeroEightFiftyWithoutLeakingTodaySessionSourceTradingDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(
				candle(FALLBACK_TRADING_DATE, LocalTime.of(9, 0), bd(2000), bd(2010), bd(1995), bd(2005), 10)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 50)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, FALLBACK_TRADING_DATE, FALLBACK_TRADING_DATE);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).tradingDate()).isEqualTo(FALLBACK_TRADING_DATE);
		assertThat(result.get(0).tradingDate()).isNotEqualTo(WEEKDAY);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(WEEKDAY), any(), any());
	}

	@Test
	void getRevealedCandlesDoesNotFallBackWhenMarketOpenAndCandleMissingForOneInstrument() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 1)))
			.thenReturn(List.of());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle poisonedFallbackCandle = candle(FALLBACK_TRADING_DATE, LocalTime.of(15, 29), bd(9999), bd(9999),
			bd(9999), bd(9999), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(poisonedFallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(FALLBACK_TRADING_DATE), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesDoesNotFallBackWhenMarketOpenAndCandlesMissingForOneInstrument() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(WEEKDAY.minusDays(1))))
			.thenReturn(List.of());
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 1)))
			.thenReturn(List.of());
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle poisonedFallbackCandle = candle(FALLBACK_TRADING_DATE, LocalTime.of(15, 29), bd(9999), bd(9999),
			bd(9999), bd(9999), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(poisonedFallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, null, null);

		assertThat(result).isEmpty();
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(FALLBACK_TRADING_DATE), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesDoesNotFallBackDuringFirstCandleWindowEvenWhenFallbackSessionExists() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		when(stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(WEEKDAY, PreparationStatus.READY))
			.thenReturn(Optional.of(fallbackSession()));
		StockCandle poisonedFallbackCandle = candle(FALLBACK_TRADING_DATE, LocalTime.of(15, 29), bd(9999), bd(9999),
			bd(9999), bd(9999), 10);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, FALLBACK_TRADING_DATE, LocalTime.MIN, END_OF_DAY))
			.thenReturn(List.of(poisonedFallbackCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> result = service.getRevealedAggregatedCandles(
			INSTRUMENT_ID, CandleInterval.ONE_DAY, WEEKDAY, WEEKDAY);

		assertThat(result).isEmpty();
		verify(stockReplaySessionRepository, never())
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(any(), any());
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(FALLBACK_TRADING_DATE), any(), any());
	}

	private static final LocalDate TUESDAY_AFTER_HOLIDAY = LocalDate.of(2026, 8, 18);
	private static final LocalDate FRIDAY_BEFORE_HOLIDAY = LocalDate.of(2026, 8, 14);
	private static final LocalDate FRIDAY_BEFORE_WEEKDAY = LocalDate.of(2026, 7, 24);

	@Test
	void getCurrentReplaySessionReturnsReadyWithSourceTradingDateOfTodayServiceDate() {
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, sourceTradingDate)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		StockReplaySessionDto session = service.getCurrentReplaySession();

		assertThat(session.ready()).isTrue();
		assertThat(session.sourceTradingDate()).isEqualTo(sourceTradingDate);
	}

	@Test
	void getCurrentReplaySessionReturnsNotReadyWithoutTradingDateWhenNoSessionRowExists() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		StockReplaySessionDto session = service.getCurrentReplaySession();

		assertThat(session.ready()).isFalse();
		assertThat(session.sourceTradingDate()).isNull();
	}

	@Test
	void getCurrentReplaySessionDoesNotLeakCandidateTradingDateOfPreparingSession() {
		LocalDate candidate = LocalDate.of(2026, 7, 24);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(
			StockReplaySession.preparing(WEEKDAY, candidate, LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)))));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		StockReplaySessionDto session = service.getCurrentReplaySession();

		assertThat(session.ready()).isFalse();
		assertThat(session.sourceTradingDate()).isNull();
	}

	@Test
	void getCurrentReplaySessionDoesNotLeakTradingDateOfFailedSession() {
		LocalDate attempted = LocalDate.of(2026, 7, 24);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(
			StockReplaySession.failed(WEEKDAY, attempted, LocalDateTime.of(WEEKDAY, LocalTime.of(8, 30)),
				"NO_DATA", LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)))));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		StockReplaySessionDto session = service.getCurrentReplaySession();

		assertThat(session.ready()).isFalse();
		assertThat(session.sourceTradingDate()).isNull();
	}

	@Test
	void getSourceTradingDateReturnsSourceTradingDateOfReadySession() {
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, sourceTradingDate)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getSourceTradingDate(WEEKDAY)).contains(sourceTradingDate);
	}

	@Test
	void getSourceTradingDateReturnsEmptyForPreparingSessionThatAlreadyHasCandidateDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(
			StockReplaySession.preparing(WEEKDAY, LocalDate.of(2026, 7, 24),
				LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)))));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getSourceTradingDate(WEEKDAY)).isEmpty();
	}

	@Test
	void getSourceTradingDateReturnsEmptyForFailedSessionThatHasSourceTradingDate() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.of(
			StockReplaySession.failed(WEEKDAY, LocalDate.of(2026, 7, 24),
				LocalDateTime.of(WEEKDAY, LocalTime.of(8, 30)), "NO_DATA",
				LocalDateTime.of(WEEKDAY, LocalTime.of(8, 0)))));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		assertThat(service.getSourceTradingDate(WEEKDAY)).isEmpty();
	}

	@Test
	void getSourceTradingDateLooksUpTheGivenServiceDateNotToday() {
		LocalDate today = LocalDate.of(2026, 7, 30);
		LocalDate pastServiceDate = WEEKDAY;
		LocalDate pastSourceTradingDate = LocalDate.of(2026, 7, 24);
		when(stockReplaySessionRepository.findByServiceDate(pastServiceDate))
			.thenReturn(Optional.of(readySession(pastServiceDate, pastSourceTradingDate)));
		StockReplayService service = service(fixedClock(today, LocalTime.of(10, 0)));

		assertThat(service.getSourceTradingDate(pastServiceDate)).contains(pastSourceTradingDate);
		verify(stockReplaySessionRepository).findByServiceDate(pastServiceDate);
		verify(stockReplaySessionRepository, never()).findByServiceDate(today);
	}

	@Test
	void getFullDayCandlesDoesNotConsultTheReplaySessionAtAll() {
		when(stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(INSTRUMENT_ID, WEEKDAY))
			.thenReturn(List.of(
				candle(LocalTime.of(9, 0), bd(1000), bd(1010)),
				candle(LocalTime.of(15, 27), bd(1020), bd(1030))));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		List<StockCandleDto> candles = service.getFullDayCandles(INSTRUMENT_ID, WEEKDAY);

		assertThat(candles).extracting(StockCandleDto::candleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(15, 27));
		verifyNoInteractions(stockReplaySessionRepository);
	}

	@Test
	void getPreviousTradingDayCloseSkipsTheWeekendWhenResolvingPreviousBusinessDay() {
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FRIDAY_BEFORE_WEEKDAY))
			.thenReturn(Optional.of(candle(FRIDAY_BEFORE_WEEKDAY, LocalTime.of(15, 27),
				bd(1000), bd(1010), bd(990), bd(1005), 10)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		assertThat(service.getPreviousTradingDayClose(INSTRUMENT_ID, WEEKDAY))
			.contains(BigDecimal.valueOf(1005));
		ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			eq(INSTRUMENT_ID), dateCaptor.capture());
		assertThat(dateCaptor.getValue()).isEqualTo(FRIDAY_BEFORE_WEEKDAY);
		assertThat(dateCaptor.getValue()).isNotEqualTo(WEEKDAY.minusDays(1));
	}

	@Test
	void getPreviousTradingDayCloseSkipsHolidaysAsWellAsTheWeekend() {
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FRIDAY_BEFORE_HOLIDAY))
			.thenReturn(Optional.of(candle(FRIDAY_BEFORE_HOLIDAY, LocalTime.of(15, 29),
				bd(2000), bd(2010), bd(1990), bd(2007), 10)));
		StockReplayService service = service(fixedClock(TUESDAY_AFTER_HOLIDAY, LocalTime.of(8, 45)));

		assertThat(service.getPreviousTradingDayClose(INSTRUMENT_ID, TUESDAY_AFTER_HOLIDAY))
			.contains(BigDecimal.valueOf(2007));
		verify(stockCandleRepository).findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FRIDAY_BEFORE_HOLIDAY);
	}

	@Test
	void getPreviousTradingDayCloseReturnsEmptyWhenPreviousBusinessDayHasNoCandle() {
		when(stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
			INSTRUMENT_ID, FRIDAY_BEFORE_WEEKDAY))
			.thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 45)));

		assertThat(service.getPreviousTradingDayClose(INSTRUMENT_ID, WEEKDAY)).isEmpty();
		verifyNoInteractions(stockReplaySessionRepository);
	}
}
