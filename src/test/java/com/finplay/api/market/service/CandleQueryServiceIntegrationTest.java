// 픽스처 분봉 + 고정 Clock으로 실제 MySQL을 거쳐 캔들 API의 장중·첫분봉·마감후·미준비 세션 시나리오와(이슈 #143) 주식
// 일/주/월봉 집계·1분봉 회귀·코인 interval 위임을 검증하는 통합 테스트다.
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

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	private Clock clockAt(LocalDate date, LocalTime time) {
		return Clock.fixed(LocalDateTime.of(date, time).atZone(KST).toInstant(), KST);
	}

	private CandleQueryService candleQueryServiceAt(Clock clock) {
		// 코인 경로를 건드리지 않는 기존(1분봉 회귀) 테스트들은 빈 FakeCryptoCandleProvider로 충분하다.
		return candleQueryServiceAt(clock, new FakeCryptoCandleProvider());
	}

	// 이슈 #143(013): 코인 interval 위임 검증에서는 미리 시드한 FakeCryptoCandleProvider 인스턴스를 그대로 넘겨받아야 하므로
	// 별도 오버로드를 둔다.
	private CandleQueryService candleQueryServiceAt(Clock clock, FakeCryptoCandleProvider cryptoCandleProvider) {
		StockReplayService stockReplayService = new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, clock, businessDayCalendar);
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

	// 이슈 #143(013): 집계(1d·1w·1M) 검증은 SOURCE_TRADING_DATE 고정값이 아니라 여러 거래일에 걸친 분봉이 필요하므로,
	// tradingDate·OHLCV·volume을 자유롭게 지정하는 전용 헬퍼를 둔다(위 saveCandle은 1분봉 회귀 테스트 전용으로 그대로 둔다).
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

	// 이슈 #143(013) 핵심 통합 시나리오: 같은 주(TD1·TD2)·다른 주(TD3)·다른 달(TD4=재생거래일)이 섞인 4개 거래일의 실제
	// 1분봉을 MySQL에 시드해 1d·1w·1M 집계의 개수·sourceTime·OHLCV를 검증하고, 같은 테스트에서 interval=1m 응답이
	// 기존 계약(재생거래일의 분봉을 그대로 노출) 그대로임을 확인한다(회귀, tasks.md ⑤).
	@Test
	void aggregatedIntervalsSpanMultipleTradingDaysWhileOneMinuteRegressionHolds() {
		Instrument instrument = saveInstrument("CDL0143");

		LocalDate td1 = LocalDate.of(2026, 3, 2); // 월 — 3월 첫째 주
		LocalDate td2 = LocalDate.of(2026, 3, 4); // 수 — td1과 같은 주
		LocalDate td3 = LocalDate.of(2026, 3, 11); // 수 — 3월이지만 td1·td2와 다른 주
		LocalDate td4 = LocalDate.of(2026, 4, 1); // 수 — 4월(다른 달), 이 세션의 재생거래일

		saveAggCandle(instrument, td1, LocalTime.of(9, 0), "10000", "10100", "9950", "10050", 100L);
		saveAggCandle(instrument, td1, LocalTime.of(9, 1), "10050", "10150", "10020", "10120", 150L);
		saveAggCandle(instrument, td2, LocalTime.of(9, 0), "20000", "20200", "19900", "20100", 200L);
		saveAggCandle(instrument, td3, LocalTime.of(9, 0), "30000", "30300", "29800", "30200", 300L);
		saveAggCandle(instrument, td4, LocalTime.of(9, 0), "40000", "40200", "39900", "40100", 400L);
		saveAggCandle(instrument, td4, LocalTime.of(9, 1), "40100", "40400", "40050", "40300", 450L);

		LocalDate aggServiceDate = LocalDate.of(2026, 5, 20);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, td4, LocalDateTime.now(), LocalDateTime.now()));

		// 15:30(마감 후) — td4(재생거래일)의 09:00·09:01 분봉까지 전부 공개된 상태.
		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));

		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", null, null);
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

		List<CandleResponse> weekly = service.getCandles(instrument.getId(), "1w", null, null);
		assertThat(weekly).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(LocalDate.of(2026, 3, 2), LocalTime.MIDNIGHT), // td1·td2가 속한 주(월요일)
				LocalDateTime.of(LocalDate.of(2026, 3, 9), LocalTime.MIDNIGHT), // td3가 속한 주
				LocalDateTime.of(LocalDate.of(2026, 3, 30), LocalTime.MIDNIGHT)); // td4가 속한 주
		assertThat(weekly.get(0).open()).isEqualByComparingTo("10000"); // td1·td2 묶음: 최초 open
		assertThat(weekly.get(0).high()).isEqualByComparingTo("20200"); // 두 거래일 high의 최대값
		assertThat(weekly.get(0).low()).isEqualByComparingTo("9950"); // 두 거래일 low의 최소값
		assertThat(weekly.get(0).close()).isEqualByComparingTo("20100"); // td2(가장 늦은 분봉)의 close
		assertThat(weekly.get(0).volume()).isEqualByComparingTo("450"); // 100+150+200

		List<CandleResponse> monthly = service.getCandles(instrument.getId(), "1M", null, null);
		assertThat(monthly).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(LocalDate.of(2026, 3, 1), LocalTime.MIDNIGHT), // td1·td2·td3 묶음(3월)
				LocalDateTime.of(LocalDate.of(2026, 4, 1), LocalTime.MIDNIGHT)); // td4(4월)
		assertThat(monthly.get(0).open()).isEqualByComparingTo("10000");
		assertThat(monthly.get(0).high()).isEqualByComparingTo("30300");
		assertThat(monthly.get(0).low()).isEqualByComparingTo("9950");
		assertThat(monthly.get(0).close()).isEqualByComparingTo("30200"); // td3(가장 늦은 거래일)의 close
		assertThat(monthly.get(0).volume()).isEqualByComparingTo("750"); // 100+150+200+300
		assertThat(monthly.get(1).open()).isEqualByComparingTo("40000");
		assertThat(monthly.get(1).close()).isEqualByComparingTo("40300");
		assertThat(monthly.get(1).volume()).isEqualByComparingTo("850");

		// 회귀 — 같은 테스트에서 interval=1m 응답이 기존 계약(재생거래일 td4의 분봉을 컷오프까지 그대로 노출) 그대로인지 확인한다.
		// 집계 경로(getRevealedAggregatedCandles)가 아니라 1m 전용 경로(getRevealedCandles)를 그대로 타는지가 핵심이다.
		List<CandleResponse> minute = service.getCandles(instrument.getId(), "1m", null, null);
		assertThat(minute).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td4, LocalTime.of(9, 0)),
				LocalDateTime.of(td4, LocalTime.of(9, 1)));
		assertThat(minute.get(0).close()).isEqualByComparingTo("40100");
		assertThat(minute.get(1).close()).isEqualByComparingTo("40300");
	}

	// 이슈 #143(013): 코인 일/주/월봉은 저장 없이 요청 시점에 위임되므로(MKT-008과 동일 원칙), 실제 Bithumb 호출 대신
	// FakeCryptoCandleProvider에 interval별로 시드한 뒤 CandleQueryService가 시장 분기로 올바른 interval의 시드를
	// 그대로 돌려주는지 확인한다(자동 테스트는 Fake로, 실제 빗썸 호출은 별도 외부 스모크로 구분 — C-005).
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

		List<CandleResponse> daily = service.getCandles(coinInstrument.getId(), "1d", null, null);
		assertThat(daily).hasSize(1);
		assertThat(daily.get(0).sourceTime()).isEqualTo(dailySourceTime);
		assertThat(daily.get(0).close()).isEqualByComparingTo("50800");

		List<CandleResponse> weekly = service.getCandles(coinInstrument.getId(), "1w", null, null);
		assertThat(weekly).hasSize(1);
		assertThat(weekly.get(0).sourceTime()).isEqualTo(weeklySourceTime);
		assertThat(weekly.get(0).close()).isEqualByComparingTo("52000");

		List<CandleResponse> monthly = service.getCandles(coinInstrument.getId(), "1M", null, null);
		assertThat(monthly).hasSize(1);
		assertThat(monthly.get(0).sourceTime()).isEqualTo(monthlySourceTime);
		assertThat(monthly.get(0).close()).isEqualByComparingTo("53500");

		// 1d 시드만 있는 상태에서 1m을 요청하면(다른 interval 시드) 빈 배열이어야 한다 — interval별 맵이 서로 새지 않는다.
		List<CandleResponse> minute = service.getCandles(coinInstrument.getId(), "1m", null, null);
		assertThat(minute).isEmpty();
	}
}
