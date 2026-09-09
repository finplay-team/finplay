package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.CandleResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CandleQueryServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final LocalDate NO_FALLBACK_CANDIDATE_SERVICE_DATE = LocalDate.of(2000, 1, 3);

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	private Clock clockAt(LocalDate date, LocalTime time) {
		return Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
	}

	private CandleQueryService candleQueryServiceAt(Clock clock) {
		return candleQueryServiceAt(clock, new FakeCryptoCandleProvider());
	}

	private CandleQueryService candleQueryServiceAt(Clock clock, FakeCryptoCandleProvider cryptoCandleProvider) {
		StockReplayService stockReplayService = new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, stockDailyCandleRepository, clock,
			businessDayCalendar);
		KisHistoricalReplayPriceProvider provider = new KisHistoricalReplayPriceProvider(stockReplayService);
		return new CandleQueryService(instrumentRepository, provider, cryptoCandleProvider);
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

	private void saveAggCandle(
		Instrument instrument, LocalDate tradingDate, LocalTime candleTime, String open, String high, String low,
		String close, long volume) {
		stockCandleRepository.save(StockCandle.create(
			instrument,
			tradingDate,
			candleTime,
			new BigDecimal(open),
			new BigDecimal(high),
			new BigDecimal(low),
			new BigDecimal(close),
			volume,
			"KRX_REPLAY",
			LocalDateTime.now()));
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
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

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
		Instrument instrument = saveInstrument("CDL0002");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDate = LocalDate.of(2026, 7, 30);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(9, 0, 30)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

		assertThat(candles).isEmpty();
	}

	@Test
	void oneMinuteBoundaryExposesTheNowClosedFirstCandle() {
		Instrument instrument = saveInstrument("CDL0007");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDate = LocalDate.of(2026, 8, 7);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(9, 1, 0)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

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
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

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
		LocalDate serviceDateNoSession = NO_FALLBACK_CANDIDATE_SERVICE_DATE;
		assertThat(stockReplaySessionRepository.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
			serviceDateNoSession, PreparationStatus.READY)).isEmpty();

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDateNoSession, LocalTime.of(9, 5)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

		assertThat(candles).isEmpty();
	}

	@Test
	void returnsEmptyListInsteadOfErrorWhenSessionIsStillPreparing() {
		Instrument instrument = saveInstrument("CDL0005");
		saveCandle(instrument, LocalTime.of(9, 0), "71100");
		LocalDate serviceDatePreparing = NO_FALLBACK_CANDIDATE_SERVICE_DATE.plusDays(1);
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(serviceDatePreparing, null, LocalDateTime.now()));
		assertThat(stockReplaySessionRepository.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
			serviceDatePreparing, PreparationStatus.READY)).isEmpty();

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDatePreparing, LocalTime.of(9, 5)));
		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", null, null, null).content();

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

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(15, 30)));
		LocalDateTime from = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1));
		LocalDateTime to = LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 3));

		List<CandleResponse> candles = service.getCandles(instrument.getId(), "1m", from, to, null).content();

		assertThat(candles).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 2)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 3)));
	}

	@Test
	void aggregatedIntervalsSpanMultipleTradingDaysWhileOneMinuteRegressionHolds() {
		Instrument instrument = saveInstrument("CDL0143");

		LocalDate td1 = LocalDate.of(2026, 3, 2);
		LocalDate td2 = LocalDate.of(2026, 3, 4);
		LocalDate td3 = LocalDate.of(2026, 3, 11);
		LocalDate td4 = LocalDate.of(2026, 4, 1);

		saveAggCandle(instrument, td1, LocalTime.of(9, 0), "10000", "10100", "9950", "10050", 100L);
		saveAggCandle(instrument, td1, LocalTime.of(9, 1), "10050", "10150", "10020", "10120", 150L);
		saveAggCandle(instrument, td2, LocalTime.of(9, 0), "20000", "20200", "19900", "20100", 200L);
		saveAggCandle(instrument, td3, LocalTime.of(9, 0), "30000", "30300", "29800", "30200", 300L);
		saveAggCandle(instrument, td4, LocalTime.of(9, 0), "40000", "40200", "39900", "40100", 400L);
		saveAggCandle(instrument, td4, LocalTime.of(9, 1), "40100", "40400", "40050", "40300", 450L);

		LocalDate aggServiceDate = LocalDate.of(2026, 5, 20);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, td4, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));

		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", null, null, null).content();
		assertThat(daily).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td1, LocalTime.MIDNIGHT),
				LocalDateTime.of(td2, LocalTime.MIDNIGHT),
				LocalDateTime.of(td3, LocalTime.MIDNIGHT),
				LocalDateTime.of(td4, LocalTime.MIDNIGHT));
		assertThat(daily.get(0).open()).isEqualByComparingTo("10000");
		assertThat(daily.get(0).high()).isEqualByComparingTo("10150");
		assertThat(daily.get(0).low()).isEqualByComparingTo("9950");
		assertThat(daily.get(0).close()).isEqualByComparingTo("10120");
		assertThat(daily.get(0).volume()).isEqualByComparingTo("250");
		assertThat(daily.get(3).open()).isEqualByComparingTo("40000");
		assertThat(daily.get(3).high()).isEqualByComparingTo("40400");
		assertThat(daily.get(3).low()).isEqualByComparingTo("39900");
		assertThat(daily.get(3).close()).isEqualByComparingTo("40300");
		assertThat(daily.get(3).volume()).isEqualByComparingTo("850");

		List<CandleResponse> weekly = service.getCandles(instrument.getId(), "1w", null, null, null).content();
		assertThat(weekly).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(LocalDate.of(2026, 3, 2), LocalTime.MIDNIGHT),
				LocalDateTime.of(LocalDate.of(2026, 3, 9), LocalTime.MIDNIGHT),
				LocalDateTime.of(LocalDate.of(2026, 3, 30), LocalTime.MIDNIGHT));
		assertThat(weekly.get(0).open()).isEqualByComparingTo("10000");
		assertThat(weekly.get(0).high()).isEqualByComparingTo("20200");
		assertThat(weekly.get(0).low()).isEqualByComparingTo("9950");
		assertThat(weekly.get(0).close()).isEqualByComparingTo("20100");
		assertThat(weekly.get(0).volume()).isEqualByComparingTo("450");

		List<CandleResponse> monthly = service.getCandles(instrument.getId(), "1M", null, null, null).content();
		assertThat(monthly).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(LocalDate.of(2026, 3, 1), LocalTime.MIDNIGHT),
				LocalDateTime.of(LocalDate.of(2026, 4, 1), LocalTime.MIDNIGHT));
		assertThat(monthly.get(0).open()).isEqualByComparingTo("10000");
		assertThat(monthly.get(0).high()).isEqualByComparingTo("30300");
		assertThat(monthly.get(0).low()).isEqualByComparingTo("9950");
		assertThat(monthly.get(0).close()).isEqualByComparingTo("30200");
		assertThat(monthly.get(0).volume()).isEqualByComparingTo("750");
		assertThat(monthly.get(1).open()).isEqualByComparingTo("40000");
		assertThat(monthly.get(1).close()).isEqualByComparingTo("40300");
		assertThat(monthly.get(1).volume()).isEqualByComparingTo("850");

		List<CandleResponse> minute = service.getCandles(instrument.getId(), "1m", null, null, null).content();
		assertThat(minute).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td4, LocalTime.of(9, 0)),
				LocalDateTime.of(td4, LocalTime.of(9, 1)));
		assertThat(minute.get(0).close()).isEqualByComparingTo("40100");
		assertThat(minute.get(1).close()).isEqualByComparingTo("40300");
	}

	private void saveDailyArchiveCandle(
		Instrument instrument, LocalDate tradingDate, String open, String high, String low, String close, long volume) {
		stockDailyCandleRepository.save(StockDailyCandle.create(
			instrument, tradingDate,
			new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume,
			"KIS_DAILY", LocalDateTime.now()));
	}

	@Test
	void aggregatedDailyIntervalPrefersArchiveOverOneMinuteAggregationPerTradingDate() {
		Instrument instrument = saveInstrument("CDL0506A");

		LocalDate td1 = LocalDate.of(2023, 8, 21);
		LocalDate td2 = LocalDate.of(2026, 6, 3);
		LocalDate td3 = LocalDate.of(2026, 7, 1);

		saveDailyArchiveCandle(instrument, td1, "10000", "10500", "9900", "10300", 999L);

		saveAggCandle(instrument, td2, LocalTime.of(9, 0), "20000", "20200", "19900", "20100", 200L);

		saveAggCandle(instrument, td3, LocalTime.of(9, 0), "40000", "40200", "39900", "40100", 400L);
		saveDailyArchiveCandle(instrument, td3, "1", "99999", "1", "99999", 1L);

		LocalDate aggServiceDate = LocalDate.of(2026, 8, 12);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, td3, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		LocalDateTime from = LocalDateTime.of(td1, LocalTime.MIDNIGHT);
		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", from, null, null).content();

		assertThat(daily).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td1, LocalTime.MIDNIGHT),
				LocalDateTime.of(td2, LocalTime.MIDNIGHT),
				LocalDateTime.of(td3, LocalTime.MIDNIGHT));

		assertThat(daily.get(0).open()).isEqualByComparingTo("10000");
		assertThat(daily.get(0).high()).isEqualByComparingTo("10500");
		assertThat(daily.get(0).low()).isEqualByComparingTo("9900");
		assertThat(daily.get(0).close()).isEqualByComparingTo("10300");
		assertThat(daily.get(0).volume()).isEqualByComparingTo("999");

		assertThat(daily.get(1).close()).isEqualByComparingTo("20100");
		assertThat(daily.get(1).volume()).isEqualByComparingTo("200");

		assertThat(daily.get(2).high()).isEqualByComparingTo("40200");
		assertThat(daily.get(2).close()).isEqualByComparingTo("40100");
		assertThat(daily.get(2).volume()).isEqualByComparingTo("400");
	}

	@Test
	@Transactional
	void aggregatedDailyIntervalReturnsCorrectLatestTwoHundredBucketsWhenDataSpansMoreThanTwoHundredTradingDays() {
		Instrument instrument = saveInstrument("CDL0155");
		LocalDate firstTradingDate = LocalDate.of(2020, 1, 2);
		int totalTradingDays = 210;
		List<LocalDate> tradingDates = new ArrayList<>();
		for (int i = 0; i < totalTradingDays; i++) {
			LocalDate tradingDate = firstTradingDate.plusDays(i);
			tradingDates.add(tradingDate);
			saveAggCandle(
				instrument, tradingDate, LocalTime.of(9, 0), "1000", "1010", "990", String.valueOf(1000 + i), 10);
		}
		LocalDate sourceTradingDate = tradingDates.get(totalTradingDays - 1);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", null, null, null).content();

		assertThat(daily).hasSize(200);
		assertThat(daily.get(0).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(10), LocalTime.MIDNIGHT));
		assertThat(daily.get(199).sourceTime())
			.isEqualTo(LocalDateTime.of(sourceTradingDate, LocalTime.MIDNIGHT));
		assertThat(daily.get(199).close()).isEqualByComparingTo(String.valueOf(1000 + totalTradingDays - 1));
	}

	@Test
	@Transactional
	void aggregatedDailyIntervalNarrowingCombinesArchiveAndOneMinuteWhenCombinedTotalExceedsTwoHundred() {
		Instrument instrument = saveInstrument("CDL0515N");
		LocalDate firstTradingDate = LocalDate.of(2018, 1, 2);
		int archiveDays = 110;
		int oneMinuteDays = 100;
		int totalTradingDays = archiveDays + oneMinuteDays;
		List<LocalDate> tradingDates = new ArrayList<>();
		for (int i = 0; i < totalTradingDays; i++) {
			LocalDate tradingDate = firstTradingDate.plusDays(i);
			tradingDates.add(tradingDate);
			if (i < archiveDays) {
				saveDailyArchiveCandle(
					instrument, tradingDate, "1000", "1010", "990", String.valueOf(1000 + i), 10L);
			} else {
				saveAggCandle(
					instrument, tradingDate, LocalTime.of(9, 0), "1000", "1010", "990", String.valueOf(1000 + i), 10);
			}
		}
		LocalDate sourceTradingDate = tradingDates.get(totalTradingDays - 1);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", null, null, null).content();

		assertThat(daily).hasSize(200);
		assertThat(daily).extracting(CandleResponse::sourceTime)
			.doesNotContain(LocalDateTime.of(tradingDates.get(9), LocalTime.MIDNIGHT));
		assertThat(daily.get(0).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(10), LocalTime.MIDNIGHT));
		assertThat(daily.get(0).close()).isEqualByComparingTo(String.valueOf(1000 + 10));
		assertThat(daily.get(99).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(109), LocalTime.MIDNIGHT));
		assertThat(daily.get(99).close()).isEqualByComparingTo(String.valueOf(1000 + 109));
		assertThat(daily.get(100).sourceTime())
			.isEqualTo(LocalDateTime.of(tradingDates.get(110), LocalTime.MIDNIGHT));
		assertThat(daily.get(100).close()).isEqualByComparingTo(String.valueOf(1000 + 110));
		assertThat(daily.get(199).sourceTime()).isEqualTo(LocalDateTime.of(sourceTradingDate, LocalTime.MIDNIGHT));
		assertThat(daily.get(199).close()).isEqualByComparingTo(String.valueOf(1000 + totalTradingDays - 1));
	}

	@Test
	@Transactional
	void aggregatedWeeklyIntervalIncludesTheEntireOldestSurvivingBucketWhenNarrowingIsTriggered() {
		Instrument instrument = saveInstrument("CDL0162");
		LocalDate firstMonday = LocalDate.of(2020, 1, 6);
		int totalWeeks = 205;
		List<LocalDate> mondays = new ArrayList<>();
		for (int i = 0; i < totalWeeks; i++) {
			LocalDate monday = firstMonday.plusWeeks(i);
			LocalDate friday = monday.plusDays(4);
			mondays.add(monday);
			saveAggCandle(instrument, monday, LocalTime.of(9, 0), "1000", "1005", "995", "1002", 10);
			saveAggCandle(instrument, friday, LocalTime.of(9, 0), "2000", "2005", "1995", "2002", 20);
		}
		LocalDate sourceTradingDate = mondays.get(totalWeeks - 1).plusDays(4);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));
		LocalDate explicitFrom = firstMonday.minusYears(3);

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		List<CandleResponse> weekly = service.getCandles(
			instrument.getId(), "1w", explicitFrom.atStartOfDay(), null, null).content();

		assertThat(weekly).hasSize(200);
		LocalDate oldestSurvivingMonday = mondays.get(5);
		assertThat(weekly.get(0).sourceTime()).isEqualTo(LocalDateTime.of(oldestSurvivingMonday, LocalTime.MIDNIGHT));
		assertThat(weekly.get(0).open()).isEqualByComparingTo("1000");
		assertThat(weekly.get(0).close()).isEqualByComparingTo("2002");
		assertThat(weekly.get(0).high()).isEqualByComparingTo("2005");
		assertThat(weekly.get(0).low()).isEqualByComparingTo("995");
	}

	@Test
	void cryptoIntervalsReturnTheFakeProviderSeedMatchingEachInterval() {
		Instrument coinInstrument = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "AGGAGG", "집계코인통합테스트", new BigDecimal("1"), 5000, true, LocalDateTime.now()));

		FakeCryptoCandleProvider cryptoCandleProvider = new FakeCryptoCandleProvider();
		LocalDateTime dailySourceTime = LocalDateTime.of(2026, 3, 1, 0, 0);
		LocalDateTime weeklySourceTime = LocalDateTime.of(2026, 3, 2, 0, 0);
		LocalDateTime monthlySourceTime = LocalDateTime.of(2026, 3, 1, 0, 0);
		cryptoCandleProvider.setCandles(
			coinInstrument.getSymbol(),
			CandleInterval.ONE_DAY,
			List.of(new CryptoCandleDto(
				dailySourceTime, new BigDecimal("50000"), new BigDecimal("51000"), new BigDecimal("49500"),
				new BigDecimal("50800"), new BigDecimal("12.5"))));
		cryptoCandleProvider.setCandles(
			coinInstrument.getSymbol(),
			CandleInterval.ONE_WEEK,
			List.of(new CryptoCandleDto(
				weeklySourceTime, new BigDecimal("48000"), new BigDecimal("53000"), new BigDecimal("47500"),
				new BigDecimal("52000"), new BigDecimal("40.25"))));
		cryptoCandleProvider.setCandles(
			coinInstrument.getSymbol(),
			CandleInterval.ONE_MONTH,
			List.of(new CryptoCandleDto(
				monthlySourceTime, new BigDecimal("45000"), new BigDecimal("55000"), new BigDecimal("44000"),
				new BigDecimal("53500"), new BigDecimal("310.0"))));

		CandleQueryService service = candleQueryServiceAt(
			clockAt(LocalDate.of(2026, 5, 20), LocalTime.of(12, 0)), cryptoCandleProvider);

		List<CandleResponse> daily = service.getCandles(coinInstrument.getId(), "1d", null, null, null).content();
		assertThat(daily).hasSize(1);
		assertThat(daily.get(0).sourceTime()).isEqualTo(dailySourceTime);
		assertThat(daily.get(0).close()).isEqualByComparingTo("50800");

		List<CandleResponse> weekly = service.getCandles(coinInstrument.getId(), "1w", null, null, null).content();
		assertThat(weekly).hasSize(1);
		assertThat(weekly.get(0).sourceTime()).isEqualTo(weeklySourceTime);
		assertThat(weekly.get(0).close()).isEqualByComparingTo("52000");

		List<CandleResponse> monthly = service.getCandles(coinInstrument.getId(), "1M", null, null, null).content();
		assertThat(monthly).hasSize(1);
		assertThat(monthly.get(0).sourceTime()).isEqualTo(monthlySourceTime);
		assertThat(monthly.get(0).close()).isEqualByComparingTo("53500");

		List<CandleResponse> minute = service.getCandles(coinInstrument.getId(), "1m", null, null, null).content();
		assertThat(minute).isEmpty();
	}

	@Test
	@Transactional
	void dailyIntervalCursorPaginationCoversAllTradingDaysWithoutDuplicationAndEndsWithOneEmptyPage() {
		Instrument instrument = saveInstrument("CDL0473A");
		LocalDate firstTradingDate = LocalDate.of(2019, 1, 2);
		int totalTradingDays = 400;
		List<LocalDate> tradingDates = new ArrayList<>();
		for (int i = 0; i < totalTradingDays; i++) {
			LocalDate tradingDate = firstTradingDate.plusDays(i);
			tradingDates.add(tradingDate);
			saveAggCandle(
				instrument, tradingDate, LocalTime.of(9, 0), "1000", "1010", "990", String.valueOf(1000 + i), 10);
		}
		LocalDate sourceTradingDate = tradingDates.get(totalTradingDays - 1);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));

		CandleListResponse page1 = service.getCandles(instrument.getId(), "1d", null, null, null);
		assertThat(page1.content()).hasSize(200);
		assertThat(page1.hasNext()).isTrue();
		assertThat(page1.nextCursor()).isNotNull();

		CandleListResponse page2 = service.getCandles(instrument.getId(), "1d", null, null, page1.nextCursor());
		assertThat(page2.content()).hasSize(200);
		assertThat(page2.hasNext()).isTrue();
		assertThat(page2.nextCursor()).isNotNull();

		CandleListResponse page3 = service.getCandles(instrument.getId(), "1d", null, null, page2.nextCursor());
		assertThat(page3.content()).isEmpty();
		assertThat(page3.hasNext()).isFalse();
		assertThat(page3.nextCursor()).isNull();

		CandleListResponse page3Again = service.getCandles(instrument.getId(), "1d", null, null, page2.nextCursor());
		assertThat(page3Again.content()).isEmpty();
		assertThat(page3Again.hasNext()).isFalse();

		List<LocalDateTime> combinedSourceTimes = new ArrayList<>();
		combinedSourceTimes.addAll(page2.content().stream().map(CandleResponse::sourceTime).toList());
		combinedSourceTimes.addAll(page1.content().stream().map(CandleResponse::sourceTime).toList());
		List<LocalDateTime> expectedSourceTimes = tradingDates.stream()
			.map(date -> LocalDateTime.of(date, LocalTime.MIDNIGHT))
			.toList();
		assertThat(combinedSourceTimes).containsExactlyElementsOf(expectedSourceTimes);
		assertThat(combinedSourceTimes).doesNotHaveDuplicates();

		LocalDateTime page1From = page1.content().get(0).sourceTime();
		LocalDateTime page1To = page1.content().get(page1.content().size() - 1).sourceTime();
		CandleListResponse directPage1 = service.getCandles(instrument.getId(), "1d", page1From, page1To, null);
		assertThat(directPage1.content()).containsExactlyElementsOf(page1.content());

		LocalDateTime page2From = page2.content().get(0).sourceTime();
		LocalDateTime page2To = page2.content().get(page2.content().size() - 1).sourceTime();
		CandleListResponse directPage2 = service.getCandles(instrument.getId(), "1d", page2From, page2To, null);
		assertThat(directPage2.content()).containsExactlyElementsOf(page2.content());
	}

	@Test
	@Transactional
	void weeklyIntervalCursorPaginationCoversAllWeeksWithoutDuplicationOrGaps() {
		Instrument instrument = saveInstrument("CDL0473B");
		LocalDate firstMonday = LocalDate.of(2015, 1, 5);
		int totalWeeks = 250;
		List<LocalDate> mondays = new ArrayList<>();
		for (int i = 0; i < totalWeeks; i++) {
			LocalDate monday = firstMonday.plusWeeks(i);
			mondays.add(monday);
			saveAggCandle(instrument, monday, LocalTime.of(9, 0), "1000", "1010", "990", String.valueOf(1000 + i), 10);
		}
		LocalDate sourceTradingDate = mondays.get(totalWeeks - 1);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));

		CandleListResponse page1 = service.getCandles(instrument.getId(), "1w", null, null, null);
		assertThat(page1.content()).hasSize(200);
		assertThat(page1.hasNext()).isTrue();
		assertThat(page1.nextCursor()).isNotNull();

		CandleListResponse page2 = service.getCandles(instrument.getId(), "1w", null, null, page1.nextCursor());
		assertThat(page2.content()).hasSize(50);
		assertThat(page2.hasNext()).isFalse();
		assertThat(page2.nextCursor()).isNull();

		List<LocalDateTime> combined = new ArrayList<>();
		combined.addAll(page2.content().stream().map(CandleResponse::sourceTime).toList());
		combined.addAll(page1.content().stream().map(CandleResponse::sourceTime).toList());
		List<LocalDateTime> expected = mondays.stream().map(date -> LocalDateTime.of(date, LocalTime.MIDNIGHT))
			.toList();
		assertThat(combined).containsExactlyElementsOf(expected);
		assertThat(combined).doesNotHaveDuplicates();
	}

	@Test
	@Transactional
	void monthlyIntervalCursorPaginationCoversAllMonthsWithoutDuplicationOrGaps() {
		Instrument instrument = saveInstrument("CDL0473C");
		LocalDate firstMonth = LocalDate.of(2005, 1, 1);
		int totalMonths = 250;
		List<LocalDate> months = new ArrayList<>();
		for (int i = 0; i < totalMonths; i++) {
			LocalDate monthStart = firstMonth.plusMonths(i);
			months.add(monthStart);
			saveAggCandle(
				instrument, monthStart, LocalTime.of(9, 0), "1000", "1010", "990", String.valueOf(1000 + i), 10);
		}
		LocalDate sourceTradingDate = months.get(totalMonths - 1);
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));

		CandleListResponse page1 = service.getCandles(instrument.getId(), "1M", null, null, null);
		assertThat(page1.content()).hasSize(200);
		assertThat(page1.hasNext()).isTrue();
		assertThat(page1.nextCursor()).isNotNull();

		CandleListResponse page2 = service.getCandles(instrument.getId(), "1M", null, null, page1.nextCursor());
		assertThat(page2.content()).hasSize(50);
		assertThat(page2.hasNext()).isFalse();
		assertThat(page2.nextCursor()).isNull();

		List<LocalDateTime> combined = new ArrayList<>();
		combined.addAll(page2.content().stream().map(CandleResponse::sourceTime).toList());
		combined.addAll(page1.content().stream().map(CandleResponse::sourceTime).toList());
		List<LocalDateTime> expected = months.stream().map(date -> LocalDateTime.of(date, LocalTime.MIDNIGHT)).toList();
		assertThat(combined).containsExactlyElementsOf(expected);
		assertThat(combined).doesNotHaveDuplicates();
	}

	@Test
	@Transactional
	void stockOneMinuteCursorNeverAdvancesPastTheSingleReplayDayEvenAtTwoHundredCandles() {
		Instrument instrument = saveInstrument("CDL0473D");
		LocalTime candleStart = LocalTime.of(9, 0);
		for (int i = 0; i < 200; i++) {
			saveCandle(instrument, candleStart.plusMinutes(i), String.valueOf(71000 + i));
		}
		LocalDate serviceDate = LocalDate.of(2026, 8, 10);
		saveReadySession(serviceDate);

		CandleQueryService service = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(15, 30)));

		CandleListResponse noCursor = service.getCandles(instrument.getId(), "1m", null, null, null);
		assertThat(noCursor.content()).hasSize(200);
		assertThat(noCursor.hasNext()).isFalse();
		assertThat(noCursor.nextCursor()).isNull();

		String someCursor = CandleCursor.encode(LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(10, 0)));
		CandleListResponse withCursor = service.getCandles(instrument.getId(), "1m", null, null, someCursor);
		assertThat(withCursor.content()).containsExactlyElementsOf(noCursor.content());
		assertThat(withCursor.hasNext()).isFalse();
		assertThat(withCursor.nextCursor()).isNull();
	}

	@Test
	@Transactional
	void noCursorRequestsMatchPrePaginationValuesAcrossFourIntervalsAndBothMarkets() {
		Instrument stock = saveInstrument("CDL0473E");
		saveCandle(stock, LocalTime.of(9, 0), "71100");
		saveCandle(stock, LocalTime.of(9, 1), "71200");
		saveCandle(stock, LocalTime.of(9, 2), "71300");
		LocalDate serviceDate = LocalDate.of(2026, 8, 11);
		saveReadySession(serviceDate);

		LocalDate td1 = LocalDate.of(2026, 6, 1);
		LocalDate td2 = LocalDate.of(2026, 6, 8);
		LocalDate td3 = LocalDate.of(2026, 7, 6);
		saveAggCandle(stock, td1, LocalTime.of(9, 0), "10000", "10100", "9950", "10050", 100L);
		saveAggCandle(stock, td2, LocalTime.of(9, 0), "20000", "20200", "19900", "20100", 200L);
		saveAggCandle(stock, td3, LocalTime.of(9, 0), "30000", "30300", "29800", "30200", 300L);

		LocalDate aggServiceDate = td3.plusDays(10);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, td3, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService stockOneMinuteService = candleQueryServiceAt(clockAt(serviceDate, LocalTime.of(15, 30)));
		CandleListResponse stockMinute = stockOneMinuteService.getCandles(stock.getId(), "1m", null, null, null);
		assertThat(stockMinute.content()).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 0)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 1)),
				LocalDateTime.of(SOURCE_TRADING_DATE, LocalTime.of(9, 2)));
		assertThat(stockMinute.hasNext()).isFalse();
		assertThat(stockMinute.nextCursor()).isNull();

		CandleQueryService aggService = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		CandleListResponse stockDaily = aggService.getCandles(stock.getId(), "1d", null, null, null);
		assertThat(stockDaily.content()).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td1, LocalTime.MIDNIGHT),
				LocalDateTime.of(td2, LocalTime.MIDNIGHT),
				LocalDateTime.of(td3, LocalTime.MIDNIGHT));
		assertThat(stockDaily.hasNext()).isFalse();
		assertThat(stockDaily.nextCursor()).isNull();

		CandleListResponse stockWeekly = aggService.getCandles(stock.getId(), "1w", null, null, null);
		assertThat(stockWeekly.content()).hasSize(3);
		assertThat(stockWeekly.hasNext()).isFalse();
		assertThat(stockWeekly.nextCursor()).isNull();

		CandleListResponse stockMonthly = aggService.getCandles(stock.getId(), "1M", null, null, null);
		assertThat(stockMonthly.content()).hasSize(2);
		assertThat(stockMonthly.hasNext()).isFalse();
		assertThat(stockMonthly.nextCursor()).isNull();

		Instrument coin = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "CDLC473", "무커서회귀코인", new BigDecimal("1"), 5000, true, LocalDateTime.now()));
		FakeCryptoCandleProvider cryptoCandleProvider = new FakeCryptoCandleProvider();
		LocalDateTime minuteSourceTime = LocalDateTime.of(2026, 6, 1, 9, 0);
		LocalDateTime dailySourceTime = LocalDateTime.of(2026, 6, 1, 0, 0);
		LocalDateTime weeklySourceTime = LocalDateTime.of(2026, 6, 8, 0, 0);
		LocalDateTime monthlySourceTime = LocalDateTime.of(2026, 6, 1, 0, 0);
		cryptoCandleProvider.setCandles(coin.getSymbol(), CandleInterval.ONE_MINUTE, List.of(new CryptoCandleDto(
			minuteSourceTime, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
			new BigDecimal("100.5"), new BigDecimal("2.0"))));
		cryptoCandleProvider.setCandles(coin.getSymbol(), CandleInterval.ONE_DAY, List.of(new CryptoCandleDto(
			dailySourceTime, new BigDecimal("50000"), new BigDecimal("51000"), new BigDecimal("49500"),
			new BigDecimal("50800"), new BigDecimal("12.5"))));
		cryptoCandleProvider.setCandles(coin.getSymbol(), CandleInterval.ONE_WEEK, List.of(new CryptoCandleDto(
			weeklySourceTime, new BigDecimal("48000"), new BigDecimal("53000"), new BigDecimal("47500"),
			new BigDecimal("52000"), new BigDecimal("40.25"))));
		cryptoCandleProvider.setCandles(coin.getSymbol(), CandleInterval.ONE_MONTH, List.of(new CryptoCandleDto(
			monthlySourceTime, new BigDecimal("45000"), new BigDecimal("55000"), new BigDecimal("44000"),
			new BigDecimal("53500"), new BigDecimal("310.0"))));

		CandleQueryService cryptoService = candleQueryServiceAt(
			clockAt(LocalDate.of(2026, 6, 10), LocalTime.of(12, 0)), cryptoCandleProvider);

		CandleListResponse cryptoMinute = cryptoService.getCandles(coin.getId(), "1m", null, null, null);
		assertThat(cryptoMinute.content()).hasSize(1);
		assertThat(cryptoMinute.content().get(0).sourceTime()).isEqualTo(minuteSourceTime);
		assertThat(cryptoMinute.hasNext()).isFalse();
		assertThat(cryptoMinute.nextCursor()).isNull();

		CandleListResponse cryptoDaily = cryptoService.getCandles(coin.getId(), "1d", null, null, null);
		assertThat(cryptoDaily.content()).hasSize(1);
		assertThat(cryptoDaily.content().get(0).sourceTime()).isEqualTo(dailySourceTime);
		assertThat(cryptoDaily.hasNext()).isFalse();
		assertThat(cryptoDaily.nextCursor()).isNull();

		CandleListResponse cryptoWeekly = cryptoService.getCandles(coin.getId(), "1w", null, null, null);
		assertThat(cryptoWeekly.content()).hasSize(1);
		assertThat(cryptoWeekly.content().get(0).sourceTime()).isEqualTo(weeklySourceTime);
		assertThat(cryptoWeekly.hasNext()).isFalse();
		assertThat(cryptoWeekly.nextCursor()).isNull();

		CandleListResponse cryptoMonthly = cryptoService.getCandles(coin.getId(), "1M", null, null, null);
		assertThat(cryptoMonthly.content()).hasSize(1);
		assertThat(cryptoMonthly.content().get(0).sourceTime()).isEqualTo(monthlySourceTime);
		assertThat(cryptoMonthly.hasNext()).isFalse();
		assertThat(cryptoMonthly.nextCursor()).isNull();
	}
}
