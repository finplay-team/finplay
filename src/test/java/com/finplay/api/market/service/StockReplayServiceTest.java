// 고정 Clock으로 재생세션 준비상태·거래시간을 조합한 장 상태·현재가 계산을 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
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
	// 2026-07-27(월) 평일 기준일 — 주말·공휴일과 겹치지 않는 순수 평일 케이스용
	private static final LocalDate WEEKDAY = LocalDate.of(2026, 7, 27);
	// 2026-08-01(토) — 주말 CLOSED 케이스용
	private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 1);
	// holidays-2026.txt에 등록된 2026-01-01(목) — 평일이지만 공휴일인 CLOSED 케이스용
	private static final LocalDate HOLIDAY = LocalDate.of(2026, 1, 1);

	private final StockReplaySessionRepository stockReplaySessionRepository = mock(StockReplaySessionRepository.class);
	private final StockCandleRepository stockCandleRepository = mock(StockCandleRepository.class);

	private static Clock fixedClock(LocalDate date, LocalTime time) {
		return Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
	}

	private StockReplayService service(Clock clock) {
		return new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, clock, new BusinessDayCalendar());
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

	// 집계 캔들(getRevealedAggregatedCandles) 테스트 전용 — tradingDate를 임의로 지정하고 OHLC를 서로 다른 값으로
	// 만들어 max high·min low 산출을 검증할 수 있게 한다.
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

	// --- 장 상태(getMarketStatus) ---

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

	// --- 현재가(getCurrentPrice) ---

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
		// 09:02:15 — 09:01 분봉은 09:02에 마감 완료. 09:02 분봉(형성 중)은 아직 마감되지 않았다.
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		StockReplayPriceDto dto = service.getCurrentPrice(INSTRUMENT_ID);

		assertThat(dto.price()).isEqualTo(BigDecimal.valueOf(1020));
		assertThat(dto.sourceTime()).isEqualTo(LocalDateTime.of(WEEKDAY, LocalTime.of(9, 1)));
		verify(stockCandleRepository)
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1));
		// 아직 마감되지 않은 09:02 분봉 시각으로는 조회하지 않는다.
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 2));
	}

	@Test
	void getCurrentPriceReturnsUnavailableBeforeMarketOpenWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
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

	// --- 배치 현재가(getCurrentPrices, PR #97 리뷰 권장사항) ---

	@Test
	void getCurrentPricesComputesReadySessionAndMarketStatusOnlyOnceForMultipleInstruments() {
		Long secondInstrumentId = 2L;
		Long thirdInstrumentId = 3L;
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
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
		});
		// 종목과 무관한 전역 상태(재생세션 조회)는 종목 수(3개)와 무관하게 요청당 1회만 계산되어야 한다.
		verify(stockReplaySessionRepository, times(1)).findByServiceDate(WEEKDAY);
		// 반면 종목별로 실제로 달라지는 분봉 조회는 종목 수만큼(3회) 일어나야 한다.
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
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockReplayPriceDto> results = service.getCurrentPrices(List.of(INSTRUMENT_ID, secondInstrumentId));

		assertThat(results).hasSize(2);
		assertThat(results).allSatisfy(dto -> {
			assertThat(dto.sessionReady()).isFalse();
			assertThat(dto.marketStatus()).isEqualTo(StockMarketStatus.CLOSED);
			assertThat(dto.price()).isNull();
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

	// 회귀 확인 — 단건 getCurrentPrice(Long)는 getCurrentPrices(List.of(id)).get(0)에 위임하도록 리팩터링됐다
	// (PR #97 리뷰 권장사항). 배치 메서드를 여러 종목으로 직접 호출한 결과 중 한 종목분과, 그 종목 하나만으로 단건 호출한
	// 결과가 동일해야 한다 — 위임 과정에서 계산값이 달라지는 회귀가 없는지 고정한다.
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

	// --- 캔들 API 공개 컷오프(getRevealedCandles) ---

	@Test
	void getRevealedCandlesReturnsEmptyListWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(10, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesReturnsEmptyListBeforeMarketOpenWithoutQueryingCandles() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(8, 59)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesReturnsEmptyListDuringFirstCandleWindowWithoutQueryingCandles() {
		// 리뷰 확정(PR #87 차단 1): 09:00~09:00:59는 첫 분봉조차 아직 마감 전이므로 캔들 목록은 예외 없이 빈 배열이어야 한다.
		// 가격 API(getCurrentPrice)가 이 구간에서 첫 분봉의 시가를 노출하는 것과는 다른 계약이다.
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).isEmpty();
		verifyNoInteractions(stockCandleRepository);
	}

	@Test
	void getRevealedCandlesRevealsFirstCandleExactlyAtOneMinuteBoundary() {
		// 09:01:00 정각 — 컷오프 경계값. currentMinute=09:01, cutoff=09:01-1분=09:00(첫 분봉, 이제 막 마감 완료).
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
		// 09:02:15 — 09:01 분봉까지만 마감 완료, 09:02 분봉(형성 중)은 아직 노출되지 않아야 한다.
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.MIN, LocalTime.of(9, 1)))
			.thenReturn(List.of(firstCandle, secondCandle));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 2, 15)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, null, null);

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(StockCandleDto::candleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 1));
		// 첫 분봉 시각 조회(findFirst...OrderByCandleTimeAsc)는 첫 분봉 구간 전용이므로 이 시각대에서는 호출되지 않는다.
		verify(stockCandleRepository, never())
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(INSTRUMENT_ID, WEEKDAY);
	}

	@Test
	void getRevealedCandlesClipsRequestedToBeyondCutoffSoUnclosedCandleIsNeverExposed() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		// now=09:05:00 → 컷오프는 09:04(마감된 마지막 분봉). 요청 to=09:10은 컷오프보다 늦으므로 09:04로 잘려야 한다.
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
		// now=09:05:00 → 컷오프는 09:04. 요청 to=09:02는 컷오프보다 이르므로 그대로 09:02가 써야 한다.
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
		// now=09:05:00 → 컷오프 09:04. 요청 from=09:10은 컷오프보다 늦으므로 범위가 역전되어 빈 목록이어야 한다.
		LocalDateTime requestedFrom = LocalDateTime.of(WEEKDAY, LocalTime.of(9, 10));
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		List<StockCandleDto> candles = service.getRevealedCandles(INSTRUMENT_ID, requestedFrom, null);

		assertThat(candles).isEmpty();
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(any(), any(), any(), any());
	}

	@Test
	void getRevealedCandlesIgnoresDateComponentOfFromAndAlwaysScopesToSourceTradingDate() {
		// 설계 확인용: from의 날짜 성분(2099-01-01, 재생 중인 sourceTradingDate와 무관한 날짜)은 무시되고
		// LocalTime 성분(09:01)만 재생 중인 단일 거래일(WEEKDAY)의 시각 범위로 사용된다.
		// 클라이언트가 실제로 다른 날짜를 의도해 from을 보냈더라도 검증·오류 없이 조용히 시각만 반영된다 — 구현자 해석 그대로의 동작.
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDateTime fromWithUnrelatedDate = LocalDateTime.of(LocalDate.of(2099, 1, 1), LocalTime.of(9, 1));
		when(stockCandleRepository.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1), LocalTime.of(9, 4)))
			.thenReturn(List.of());
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 5, 0)));

		service.getRevealedCandles(INSTRUMENT_ID, fromWithUnrelatedDate, null);

		// sourceTradingDate(WEEKDAY)로 조회되었지 from의 날짜(2099-01-01)로는 조회되지 않았다.
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
			INSTRUMENT_ID, WEEKDAY, LocalTime.of(9, 1), LocalTime.of(9, 4));
	}

	// --- 집계 캔들 공개 상한(getRevealedAggregatedCandles, 이슈 #143 항목 ③) ---

	@Test
	void getRevealedAggregatedCandlesReturnsEmptyListWhenNoReadySession() {
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY)).thenReturn(Optional.empty());
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
		// spec.md "미완성(진행 중) 버킷 처리" 예외 — 09:01 이전에는 재생거래일에 공개된 분봉이 0개이므로
		// 그 거래일의 봉 자체를 만들지 않는다(0으로 채운 봉을 만들지 않는다).
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate priorTradingDate = WEEKDAY.minusDays(3);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			INSTRUMENT_ID, priorTradingDate, WEEKDAY.minusDays(1)))
			.thenReturn(List.of(
				candle(priorTradingDate, LocalTime.of(9, 0), bd(1000), bd(1005), bd(995), bd(1002), 10)));
		// 09:00:30 — 첫 분봉 구간, resolveRevealCutoff는 empty를 반환한다.
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
		// 09:01:00 정각 — 1분봉 경로(getRevealedCandles)와 동일한 컷오프 경계값을 재사용하는지 확인한다.
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
		// now=09:05:00 → 컷오프 09:04(1분봉과 동일 규칙). 09:00·09:01 두 분봉만 공개되었다.
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
		// 아직 마감되지 않은 09:05 분봉까지 포함하는 범위로는 조회하지 않는다 — 진행 중 버킷이 미공개 분봉을 삼키면 안 된다.
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				eq(INSTRUMENT_ID), eq(WEEKDAY), eq(LocalTime.MIN), eq(LocalTime.of(9, 5)));
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(any(), any(), any());
	}

	@Test
	void getRevealedAggregatedCandlesNeverQueriesTradingDatesAfterSourceTradingDateEvenWhenToDateIsFarInFuture() {
		// 방어 케이스(spec.md "공개 상한 — 미공개 데이터 유출 금지") — to가 재생거래일보다 훨씬 미래를 가리켜도
		// 실제 쿼리 상한은 재생거래일(WEEKDAY)을 절대 넘지 않아야 한다. 결과를 사후 필터링하는 게 아니라 애초에
		// 미래 trading_date를 쿼리 인자로 요청하지 않는지 직접 검증한다.
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
		// 재생거래일 당일 조회는 정확히 WEEKDAY로만 일어나야 한다 — farFutureToDate로는 절대 조회하지 않는다.
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
		// sourceTradingDate(WEEKDAY) 이전 날짜라 재생거래일 당일 조회는 건드리지 않는다 — 200 캡만 순수하게 검증한다.
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
		// 오래된 5개(0~4번째 날)가 잘리고 최신 200개(5~204번째 날)만 남아야 한다.
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
		// 09:00:30(첫 분봉 구간) — cutoff가 empty라 재생거래일 당일 조회 스텁이 필요 없다.
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
		// 400일 lookback floor(WEEKDAY-400)로는 조회되지 않았다 — from이 명시되면 무시되어야 한다.
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				INSTRUMENT_ID, WEEKDAY.minusDays(400), WEEKDAY.minusDays(1));
	}

	// --- 선두 partial 버킷 제외(PR #151 리뷰 차단 반영) ---

	@Test
	void getRevealedAggregatedCandlesExcludesLeadingPartialWeekBucketButIncludesNextCompleteWeekWhenFromFallsMidWeek() {
		// 리뷰어(namdongyeob) PR #151 차단 지적 재현 — interval=1w, from이 그 주의 수요일(버킷 경계인 월요일과
		// 불일치)이면 그 주의 나머지 분봉(수~금)만 모여 반쪽짜리 주봉이 만들어진다. docs/api-contracts.md 계약대로
		// 버킷 시작일(월요일)이 rangeStart(from) 이전이면 그 버킷은 응답에서 빠져야 한다. 그 다음 주(완전한 한 주)는
		// 정상적으로 포함되어야 한다.
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		// 2026-07-08(수) — 그 주 월요일은 2026-07-06. 2026-07-17(금) — 다음 주(2026-07-13 월요일 시작)의 마지막 거래일.
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
		// 회귀 확인 — from이 버킷 경계(월요일)와 정확히 일치하면 필터가 과하게 잘라내지 않고 그 주 버킷을 그대로 포함해야 한다.
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
		// interval=1M도 동일한 갭 — from이 그 달의 15일이면 1~14일 분봉이 있어도 그 달(5월) 버킷은 시작일(5/1)이
		// rangeStart(5/15) 이전이라 제외되어야 한다. 그 다음 달(6월, 완전한 한 달)은 정상적으로 포함되어야 한다.
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

	// --- 조회 하한 좁히기(narrowRangeStart, 이슈 #155) ---
	// 데이터가 쌓일수록 lookbackFloor(고정 400일·200주·200개월)나 호출자가 준 넓은 from을 그대로 쓰면 응답 200개
	// 버킷에 필요한 것보다 훨씬 많은 1분봉을 읽게 되는 버그(PR #151 리뷰에서 분리된 이슈)를 고친다. 여기서는 가벼운
	// DISTINCT 거래일 조회(stockCandleRepository의 findDistinctTradingDateBy...) 결과를 스텁해, 실제 조회에 쓰이는
	// from이 그 결과로부터 역산한 좁은 값으로 바뀌는지 검증한다. 이 스텁을 두지 않은 위의 기존 테스트들은 Mockito
	// 기본값(빈 리스트)이 반환되어 narrowRangeStart가 원래 rangeStart를 그대로 반환하므로 영향받지 않는다.

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
		// 실제로는 205일치 데이터만 있다 — 200개 버킷에 필요한 것보다 5일 더 있을 뿐, 나머지 195일(400-205)은
		// 애초에 존재하지 않는다는 것을 재현한다.
		List<LocalDate> denseRecentDates = consecutiveDaysDescending(pastEnd, 205);
		when(stockCandleRepository.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
			INSTRUMENT_ID, wideLookbackFloor, pastEnd, PageRequest.of(0, 200)))
			.thenReturn(denseRecentDates);
		when(stockCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), any(), eq(pastEnd)))
			.thenReturn(List.of());
		// 09:00:30(첫 분봉 구간) — 재생거래일 당일 쿼리는 스텁이 필요 없다.
		StockReplayService service = service(fixedClock(WEEKDAY, LocalTime.of(9, 0, 30)));

		service.getRevealedAggregatedCandles(INSTRUMENT_ID, CandleInterval.ONE_DAY, null, null);

		ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
		verify(stockCandleRepository).findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
			eq(INSTRUMENT_ID), fromCaptor.capture(), eq(pastEnd));
		// 200번째로 최신인 날짜(denseRecentDates의 마지막 원소) — 고정 400일 floor보다 훨씬 좁다.
		assertThat(fromCaptor.getValue()).isEqualTo(denseRecentDates.get(199));
		assertThat(fromCaptor.getValue()).isAfter(wideLookbackFloor);
		verify(stockCandleRepository, never())
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
				INSTRUMENT_ID, wideLookbackFloor, pastEnd);
	}

	@Test
	void getRevealedAggregatedCandlesNarrowsExplicitWideFromDateWhenActualDataIsShallow() {
		// 호출자가 실제 데이터보다 훨씬 이른 from을 명시적으로 보내도(from·to 해석 계약은 그대로 유지하면서) 조회
		// 하한은 실제 존재하는 거래일까지만 좁혀야 한다 — lookbackFloor 기본값 경로뿐 아니라 명시적 from 경로에서도
		// 같은 버그가 재현되므로 함께 고쳐야 한다(이슈 #155 완료 조건).
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate explicitFrom = WEEKDAY.minusDays(1000);
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		// 실제 데이터는 5거래일뿐이다(200개 캡에 한참 못 미침) — narrowRangeStart는 원래 rangeStart보다 넓히지
		// 않고, 존재하는 가장 이른 날짜까지만 좁혀야 한다.
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
		// 1w·1M은 버킷당 최대 거래일 수가 1보다 크므로(최대 7·31일) DISTINCT 거래일 조회 자체의 Pageable 상한도
		// 그만큼 넓어야 200개 버킷을 놓치지 않는다 — narrowRangeStart가 interval별로 올바른 fetchLimit(200×7,
		// 200×31)을 실제로 사용하는지 확인한다.
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
		// PR #162 리뷰 차단 1(실제 재현·확정) 회귀 — narrowRangeStart는 recentTradingDates를 최신→과거 순으로 훑는다.
		// 한 주(버킷)에 거래일이 여럿(월·금)이면, 그 버킷을 "처음 마주치는" 날짜는 최신순 순회 특성상 그 주의 가장
		// 늦은 거래일(금요일)이다. narrowedFloor를 그 마주친 날짜 자체로 정하면(버그) 그 버킷의 앞쪽 거래일(월~목)이
		// 뒤이은 1분봉 쿼리에서 통째로 빠진다 — narrowedFloor는 반드시 그 버킷의 시작일(월요일)이어야 한다.
		when(stockReplaySessionRepository.findByServiceDate(WEEKDAY))
			.thenReturn(Optional.of(readySession(WEEKDAY, WEEKDAY)));
		LocalDate explicitFrom = WEEKDAY.minusYears(10);
		LocalDate pastEnd = WEEKDAY.minusDays(1);
		int weekCount = 200;
		LocalDate mostRecentFriday = LocalDate.of(2026, 7, 24); // pastEnd(2026-07-26) 이전의 가장 최근 금요일
		List<LocalDate> recentTradingDates = new ArrayList<>();
		LocalDate friday = mostRecentFriday;
		for (int i = 0; i < weekCount; i++) {
			recentTradingDates.add(friday);
			recentTradingDates.add(friday.minusDays(4)); // 같은 주의 월요일
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
		// 버그가 있었다면 이 값이 oldestBucketFriday(그 버킷을 처음 마주친 날짜)가 되어 oldestBucketMonday의
		// 분봉이 쿼리 범위에서 빠졌을 것이다.
		assertThat(fromCaptor.getValue()).isEqualTo(oldestBucketMonday);
		assertThat(fromCaptor.getValue()).isNotEqualTo(oldestBucketFriday);
	}

	@Test
	void getRevealedAggregatedCandlesIncludesMonthBucketWhenFromFallsExactlyOnFirstOfMonth() {
		// 회귀 확인 — from이 버킷 경계(1일)와 정확히 일치하면 필터가 과하게 잘라내지 않고 그 달 버킷을 그대로 포함해야 한다.
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
}
