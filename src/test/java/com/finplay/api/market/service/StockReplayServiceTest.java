// 고정 Clock으로 재생세션 준비상태·거래시간을 조합한 장 상태·현재가 계산을 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
		return new StockReplayService(stockReplaySessionRepository, stockCandleRepository, clock);
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
}
