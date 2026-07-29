// 픽스처 분봉 + 고정 Clock으로 실제 MySQL을 거쳐 캔들 API의 장중·첫분봉·마감후·미준비 세션 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CandleQueryServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 22);

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	private Clock clockAt(LocalDate date, LocalTime time) {
		return Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
	}

	private CandleQueryService candleQueryServiceAt(Clock clock) {
		StockReplayService stockReplayService = new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, clock);
		KisHistoricalReplayPriceProvider provider = new KisHistoricalReplayPriceProvider(stockReplayService);
		return new CandleQueryService(instrumentRepository, provider);
	}

	private Instrument saveInstrument(String symbol) {
		return instrumentRepository.save(Instrument.create(
			Market.STOCK, symbol, "통합테스트종목", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
	}

	private void saveCandle(Instrument instrument, LocalTime candleTime, String close) {
		stockCandleRepository.save(StockCandle.create(
			instrument,
			SOURCE_TRADING_DATE,
			candleTime,
			new BigDecimal("71000"),
			new BigDecimal("71500"),
			new BigDecimal("70900"),
			new BigDecimal(close),
			1000L,
			"KRX_REPLAY",
			LocalDateTime.now()));
	}

	private void saveReadySession(LocalDate serviceDate) {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			serviceDate, SOURCE_TRADING_DATE, LocalDateTime.now(), LocalDateTime.now()));
	}

	@Test
	void duringMarketHoursExcludesTheStillOpenMinuteCandle() {
		Instrument instrument = saveInstrument("CDL0001");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		saveCandle(instrument, LocalTime.of(9, 1), "71200");
		saveCandle(instrument, LocalTime.of(9, 2), "71300");
		saveCandle(instrument, LocalTime.of(9, 3), "71400");
		saveCandle(instrument, LocalTime.of(9, 4), "71500");
		saveCandle(instrument, LocalTime.of(9, 5), "71600");
		LocalDate serviceDate = LocalDate.of(2026, 8, 6);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(9, 5)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 0)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 2)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 3)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 4)));
	}

	@Test
	void firstCandleWindowReturnsEmptyListBecauseTheFirstCandleIsNotYetClosed() {
		// 리뷰 확정(PR #87 차단 1): 09:00~09:00:59에는 첫 분봉조차 아직 마감 전이므로 캔들 목록은 빈 배열이어야 한다.
		Instrument instrument = saveInstrument("CDL0002");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDate = LocalDate.of(2026, 7, 30);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(9, 0, 30)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).isEmpty();
	}

	@Test
	void oneMinuteBoundaryExposesTheNowClosedFirstCandle() {
		// 09:01:00 정각 컷오프 경계 — 09:00 분봉이 막 마감 완료되어 처음으로 노출된다.
		Instrument instrument = saveInstrument("CDL0007");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDate = LocalDate.of(2026, 8, 7);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(9, 1, 0)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).sourceTime()).isEqualTo(LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 0)));
	}

	@Test
	void afterMarketCloseExposesAllRevealedCandlesOfTheDay() {
		Instrument instrument = saveInstrument("CDL0003");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		saveCandle(instrument, LocalTime.of(9, 1), "71200");
		saveCandle(instrument, LocalTime.of(9, 2), "71300");
		LocalDate serviceDate = LocalDate.of(2026, 7, 31);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(15, 30)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 0)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 2)));
	}

	@Test
	void returnsEmptyListInsteadOfErrorWhenNoReplaySessionExistsForServiceDate() {
		Instrument instrument = saveInstrument("CDL0004");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		// 이 서비스 날짜에는 세션을 저장하지 않는다.
		LocalDate serviceDateNoSession = LocalDate.of(2026, 8, 3);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDateNoSession, LocalTime.of(9, 5)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).isEmpty();
	}

	@Test
	void returnsEmptyListInsteadOfErrorWhenSessionIsStillPreparing() {
		Instrument instrument = saveInstrument("CDL0005");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDatePreparing = LocalDate.of(2026, 8, 4);
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(serviceDatePreparing, null, LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDatePreparing, LocalTime.of(9, 5)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null);

		assertThat(candles).isEmpty();
	}

	@Test
	void narrowingFromToReturnsOnlyRevealedCandlesWithinThatRange() {
		Instrument instrument = saveInstrument("CDL0006");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		saveCandle(instrument, LocalTime.of(9, 1), "71200");
		saveCandle(instrument, LocalTime.of(9, 2), "71300");
		saveCandle(instrument, LocalTime.of(9, 3), "71400");
		saveCandle(instrument, LocalTime.of(9, 4), "71500");
		LocalDate serviceDate = LocalDate.of(2026, 8, 5);
		saveReadySession(serviceDate);

		// 15:30(마감 후)이라 그날 공개된 분봉 전체가 후보지만, from·to로 09:01~09:03만 좁혀 요청한다.
		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(15, 30)));
		LocalDateTime from = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1));
		LocalDateTime to = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 3));

		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", from, to);

		assertThat(candles).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 2)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 3)));
	}
}
