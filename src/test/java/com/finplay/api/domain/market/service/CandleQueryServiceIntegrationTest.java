// 픽스처 분봉 + 고정 Clock으로 실제 MySQL을 거쳐 캔들 API의 장중·첫분봉·마감후·미준비 세션 시나리오와(이슈 #143) 주식
// 일/주/월봉 집계·1분봉 회귀·코인 interval 위임을 검증하는 통합 테스트다.
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
	// spec 038(QUOTE-HOLD-006) 도입 이후 "세션 없음/PREPARING → 빈 배열" 테스트 전용 — 폴백은 날짜 상한 없이 과거
	// 전체에서 마지막 READY 세션을 찾으므로(StockReplayService.findFallbackSession), 이 클래스의 다른 테스트가 커밋하는
	// 가장 이른 서비스 날짜(2026-05-20, aggregatedIntervalsSpanMultipleTradingDaysWhileOneMinuteRegressionHolds)보다도
	// 훨씬 이전인 날짜를 써야 폴백 후보가 실제로 하나도 없다는 것을 보장할 수 있다 — "그날 이후로 세션을 저장하지
	// 않았다"는 사실만으로는 이제 충분하지 않다(공유 MySQL 컨테이너에 이미 커밋된 다른 테스트의 READY 세션이 폴백
	// 후보가 되어 조용히 값을 채워 버릴 수 있다, 실제 재현 확인 — agent-mistakes.md 참고).
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
		// 코인 경로를 건드리지 않는 기존(1분봉 회귀) 테스트들은 빈 FakeCryptoCandleProvider로 충분하다.
		return candleQueryServiceAt(clock, new FakeCryptoCandleProvider());
	}

	// 이슈 #143(013): 코인 interval 위임 검증에서는 미리 시드한 FakeCryptoCandleProvider 인스턴스를 그대로 넘겨받아야 하므로
	// 별도 오버로드를 둔다.
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
		// 리뷰 확정(PR #87 차단 1): 09:00~09:00:59에는 첫 분봉조차 아직 마감 전이므로 캔들 목록은 빈 배열이어야 한다.
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
		// 09:01:00 정각 컷오프 경계 — 09:00 분봉이 막 마감 완료되어 처음으로 노출된다.
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
		// 이 서비스 날짜에는 세션을 저장하지 않는다.
		LocalDate serviceDateNoSession = NO_FALLBACK_CANDIDATE_SERVICE_DATE;
		// 폴백 후보가 실제로 없다는 것을 날짜 선택에만 맡기지 않고 직접 확인한다(spec 038 QUOTE-HOLD-002·006 회귀 방지) —
		// 이 assertion이 깨지면 "폴백 없음"이 아니라 "폴백은 있는데 우연히 안 걸렸다"는 뜻이므로 날짜를 더 당겨야 한다.
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
		// PREPARING은 findReadySession을 통과하지 못해 "세션 없음"과 같은 폴백 경로를 탄다(spec 038 QUOTE-HOLD-006:
		// 오늘 세션이 PREPARING이어도 폴백은 동작한다). 이 테스트가 검증하려는 건 "폴백 후보 자체가 없을 때는 여전히
		// 빈 배열"이므로, 후보가 없다는 것을 위와 동일하게 직접 확인한다.
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

		// 15:30(마감 후)이라 그날 공개된 분봉 전체가 후보지만, from·to로 09:01~09:03만 좁혀 요청한다.
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
				LocalDateTime.of(LocalDate.of(2026, 3, 2), LocalTime.MIDNIGHT), // td1·td2가 속한 주(월요일)
				LocalDateTime.of(LocalDate.of(2026, 3, 9), LocalTime.MIDNIGHT), // td3가 속한 주
				LocalDateTime.of(LocalDate.of(2026, 3, 30), LocalTime.MIDNIGHT)); // td4가 속한 주
		assertThat(weekly.get(0).open()).isEqualByComparingTo("10000"); // td1·td2 묶음: 최초 open
		assertThat(weekly.get(0).high()).isEqualByComparingTo("20200"); // 두 거래일 high의 최대값
		assertThat(weekly.get(0).low()).isEqualByComparingTo("9950"); // 두 거래일 low의 최소값
		assertThat(weekly.get(0).close()).isEqualByComparingTo("20100"); // td2(가장 늦은 분봉)의 close
		assertThat(weekly.get(0).volume()).isEqualByComparingTo("450"); // 100+150+200

		List<CandleResponse> monthly = service.getCandles(instrument.getId(), "1M", null, null, null).content();
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

	// 이슈 #506·spec 050(MKT-011) — 일봉 아카이브 연결. 세 거래일 중 하나만 아카이브에 있을 때 그 거래일만
	// 아카이브 값을 쓰고(결정 2: 날짜당 소스 하나), 나머지는 1분봉 집계로 채워져 봉이 하나도 빠지지 않는지 검증한다.
	// 배포 직후처럼 아카이브가 최초 적재 전인 상황(1분봉만 있음)과 아카이브가 그 날짜를 이미 채운 상황이 섞여도
	// 같은 daily 응답 하나에서 자연스럽게 이어진다는 것이 이 테스트의 핵심이다.
	@Test
	void aggregatedDailyIntervalPrefersArchiveOverOneMinuteAggregationPerTradingDate() {
		Instrument instrument = saveInstrument("CDL0506A");

		LocalDate td1 = LocalDate.of(2023, 8, 21); // 아카이브에만 있음(1분봉 보관 기간 밖의 옛 거래일이라고 가정)
		LocalDate td2 = LocalDate.of(2026, 6, 3); // 1분봉에만 있음(아카이브 배치가 아직 못 채운 최근 거래일)
		LocalDate td3 = LocalDate.of(2026, 7, 1); // 재생거래일(오늘) — 아카이브를 절대 쓰면 안 되는 날

		// td1: 아카이브 전용. 1분봉 테이블에는 이 날짜의 행이 아예 없다 — 아카이브가 없으면 이 날짜는 통째로 빠진다.
		saveDailyArchiveCandle(instrument, td1, "10000", "10500", "9900", "10300", 999L);

		// td2: 1분봉 전용(아카이브 미도달 구간). 기존 집계 경로 그대로 검증.
		saveAggCandle(instrument, td2, LocalTime.of(9, 0), "20000", "20200", "19900", "20100", 200L);

		// td3: 재생거래일. 1분봉으로만 채워져 있고, 아카이브에도 "혹시 같은 날짜 값이 있어도" 이 날은 절대 아카이브를
		// 쓰면 안 된다는 것까지 함께 검증하기 위해 아카이브에도 같은 날짜의(값이 다른) 행을 심어 둔다 — 만약 코드가
		// 실수로 재생거래일까지 아카이브를 참조하면 이 값(99999)이 응답에 새어나와 테스트가 잡아낸다.
		saveAggCandle(instrument, td3, LocalTime.of(9, 0), "40000", "40200", "39900", "40100", 400L);
		saveDailyArchiveCandle(instrument, td3, "1", "99999", "1", "99999", 1L);

		LocalDate aggServiceDate = LocalDate.of(2026, 8, 12);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, td3, LocalDateTime.now(), LocalDateTime.now()));

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		// td1(2023-08-21)이 기본 lookbackFloor(rangeEnd-400일)보다 더 과거라 from을 명시해야 조회 범위에 들어온다 —
		// 이 spec의 핵심은 "얼마나 과거까지 보여줄지"가 아니라 "그 구간에 아카이브·1분봉이 섞여도 날짜당 소스가
		// 하나로 정확히 선택되는지"이므로 lookbackFloor 자체는 이 테스트의 관심사가 아니다.
		LocalDateTime from = LocalDateTime.of(td1, LocalTime.MIDNIGHT);
		List<CandleResponse> daily = service.getCandles(instrument.getId(), "1d", from, null, null).content();

		assertThat(daily).extracting(CandleResponse::sourceTime)
			.containsExactly(
				LocalDateTime.of(td1, LocalTime.MIDNIGHT),
				LocalDateTime.of(td2, LocalTime.MIDNIGHT),
				LocalDateTime.of(td3, LocalTime.MIDNIGHT));

		// td1 — 아카이브 값 그대로.
		assertThat(daily.get(0).open()).isEqualByComparingTo("10000");
		assertThat(daily.get(0).high()).isEqualByComparingTo("10500");
		assertThat(daily.get(0).low()).isEqualByComparingTo("9900");
		assertThat(daily.get(0).close()).isEqualByComparingTo("10300");
		assertThat(daily.get(0).volume()).isEqualByComparingTo("999");

		// td2 — 1분봉 집계 값 그대로(기존 회귀).
		assertThat(daily.get(1).close()).isEqualByComparingTo("20100");
		assertThat(daily.get(1).volume()).isEqualByComparingTo("200");

		// td3 — 재생거래일은 1분봉 값이어야 한다. 아카이브의 99999가 나오면 공개 상한이 깨진 것이다.
		assertThat(daily.get(2).high()).isEqualByComparingTo("40200");
		assertThat(daily.get(2).close()).isEqualByComparingTo("40100");
		assertThat(daily.get(2).volume()).isEqualByComparingTo("400");
	}

	// 이슈 #155: 응답은 200개 버킷으로 캡되지만 그걸 만들기 위해 읽는 분봉 수 자체에는 상한이 없던 버그(PR #151
	// 리뷰에서 분리) — 실제 MySQL에 200개 버킷보다 많은 거래일(210일)을 시드해, 조회 하한을 좁히는 최적화
	// (StockReplayService.narrowRangeStart)가 실 DB 경로에서도 여전히 정확한 "최신 200개"를 반환하는지 검증한다.
	// 조회 자체가 실제로 좁혀진 범위만 읽는지는 StockReplayServiceTest의 Mockito 인자 검증(정확한 from 경계값)으로
	// 고정했으므로, 여기서는 전체 스택(실 MySQL·집계·200 캡)이 그 최적화와 맞물려도 결과가 깨지지 않는지에 집중한다.
	// @Transactional — 210개 거래일 분봉·종목·재생세션을 커밋하면(이 클래스의 다른 테스트들과 달리 이 테스트만)
	// 공유 MySQL 컨테이너(ADR-0003)에 실제로 남아 InstrumentRepositoryTest의 "정확히 28건" 단정 등 다른 클래스의
	// 개수 기반 검증을 깨뜨릴 수 있다(2026-07-30 agent-mistakes.md와 동일 패턴, 실제 재현 확인). 테스트 종료 시
	// 자동 롤백시켜 격리한다.
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
		// 210일 중 가장 오래된 10일(인덱스 0~9)은 200개 캡에 밀려 빠지고, 인덱스 10부터가 응답의 첫 봉이어야 한다.
		assertThat(daily.get(0).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(10), LocalTime.MIDNIGHT));
		assertThat(daily.get(199).sourceTime())
			.isEqualTo(LocalDateTime.of(sourceTradingDate, LocalTime.MIDNIGHT));
		assertThat(daily.get(199).close()).isEqualByComparingTo(String.valueOf(1000 + totalTradingDays - 1));
	}

	// PR #515 리뷰 권장 — 위 테스트는 210일 전부 1분봉이라 narrowRangeStart의 "두 소스 합산" 경로 자체가 검증되지
	// 않는다(아카이브가 비어 있으면 합집합이 그냥 1분봉 목록과 같다). 여기서는 오래된 110일을 아카이브 전용,
	// 최근 100일을 1분봉 전용으로 나눠 실제로 두 소스를 합쳐야만 210일(>200) 스캔이 성립하는 상황을 만든다.
	// 200개 캡에 걸려 살아남는 경계(아카이브 인덱스 10~109, 1분봉 인덱스 110~209)가 소스 전환 지점에서도 끊기거나
	// 겹치지 않는지가 이 테스트의 핵심이다.
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
		// 가장 오래된 10일(인덱스 0~9, 전부 아카이브)이 캡에 밀려 빠진다.
		assertThat(daily).extracting(CandleResponse::sourceTime)
			.doesNotContain(LocalDateTime.of(tradingDates.get(9), LocalTime.MIDNIGHT));
		// 살아남은 첫 봉(인덱스 10)은 아카이브 소스 값이다.
		assertThat(daily.get(0).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(10), LocalTime.MIDNIGHT));
		assertThat(daily.get(0).close()).isEqualByComparingTo(String.valueOf(1000 + 10));
		// 소스 전환 경계 — 인덱스 109(아카이브 마지막)와 110(1분봉 첫날)이 응답에서 나란히 이어지고 값도 정확해야
		// 한다. 아카이브는 인덱스 10부터 살아남으므로 응답에서의 위치는 (109-10)=99, (110-10)=100.
		assertThat(daily.get(99).sourceTime()).isEqualTo(LocalDateTime.of(tradingDates.get(109), LocalTime.MIDNIGHT));
		assertThat(daily.get(99).close()).isEqualByComparingTo(String.valueOf(1000 + 109));
		assertThat(daily.get(100).sourceTime())
			.isEqualTo(LocalDateTime.of(tradingDates.get(110), LocalTime.MIDNIGHT));
		assertThat(daily.get(100).close()).isEqualByComparingTo(String.valueOf(1000 + 110));
		// 마지막 봉(재생거래일, 1분봉 소스)까지 끊김 없이 이어진다.
		assertThat(daily.get(199).sourceTime()).isEqualTo(LocalDateTime.of(sourceTradingDate, LocalTime.MIDNIGHT));
		assertThat(daily.get(199).close()).isEqualByComparingTo(String.valueOf(1000 + totalTradingDays - 1));
	}

	// PR #162 리뷰 차단 1(실제 재현·확정) 회귀 — narrowRangeStart가 200번째(가장 오래 살아남는) 버킷의 시작일이
	// 아니라 그 버킷을 최신순 순회 중 "처음 마주친" 거래일(주봉이면 그 주 금요일)을 조회 하한으로 쓰면, 그 버킷의
	// 앞쪽 거래일(월요일)이 뒤이은 1분봉 쿼리에서 빠져 open이 조용히 틀린다. 실제로 좁히기가 트리거되도록(200개
	// 초과) 205주치를 시드하고, 각 주 월·금 이틀치를 서로 다른 값으로 넣어 캡에 걸려 살아남는 가장 오래된 버킷의
	// open이 월요일 값을 반영하는지(=그 버킷 전체가 조회됐는지)로 실 MySQL·집계·200 캡이 맞물린 전체 스택에서
	// 이 결함을 검증한다.
	@Test
	@Transactional
	void aggregatedWeeklyIntervalIncludesTheEntireOldestSurvivingBucketWhenNarrowingIsTriggered() {
		Instrument instrument = saveInstrument("CDL0162");
		LocalDate firstMonday = LocalDate.of(2020, 1, 6); // 월요일
		int totalWeeks = 205;
		List<LocalDate> mondays = new ArrayList<>();
		for (int i = 0; i < totalWeeks; i++) {
			LocalDate monday = firstMonday.plusWeeks(i);
			LocalDate friday = monday.plusDays(4);
			mondays.add(monday);
			saveAggCandle(instrument, monday, LocalTime.of(9, 0), "1000", "1005", "995", "1002", 10);
			saveAggCandle(instrument, friday, LocalTime.of(9, 0), "2000", "2005", "1995", "2002", 20);
		}
		LocalDate sourceTradingDate = mondays.get(totalWeeks - 1).plusDays(4); // 마지막 주 금요일
		LocalDate aggServiceDate = sourceTradingDate.plusDays(30);
		stockReplaySessionRepository.save(
			StockReplaySession.ready(aggServiceDate, sourceTradingDate, LocalDateTime.now(), LocalDateTime.now()));
		// lookbackFloor(200주)가 아니라 narrowRangeStart 자체가 하한을 정하도록, 실제 데이터(205주)보다 훨씬 이른
		// 명시적 from을 준다.
		LocalDate explicitFrom = firstMonday.minusYears(3);

		CandleQueryService service = candleQueryServiceAt(clockAt(aggServiceDate, LocalTime.of(15, 30)));
		List<CandleResponse> weekly = service.getCandles(
			instrument.getId(), "1w", explicitFrom.atStartOfDay(), null, null).content();

		assertThat(weekly).hasSize(200);
		// 205주 중 가장 오래된 5주(인덱스 0~4)는 200개 캡에 밀려 빠지고, 인덱스 5(그 주 월요일)가 응답의 첫 봉이다.
		LocalDate oldestSurvivingMonday = mondays.get(5);
		assertThat(weekly.get(0).sourceTime()).isEqualTo(LocalDateTime.of(oldestSurvivingMonday, LocalTime.MIDNIGHT));
		// 핵심 단정 — open이 월요일 값(1000)이어야 한다. 버그가 있으면 narrowRangeStart가 이 주의 금요일을 조회
		// 하한으로 써서 월요일 행이 통째로 빠지고, open이 금요일 값(2000)이 되어 버린다.
		assertThat(weekly.get(0).open()).isEqualByComparingTo("1000");
		assertThat(weekly.get(0).close()).isEqualByComparingTo("2002");
		assertThat(weekly.get(0).high()).isEqualByComparingTo("2005");
		assertThat(weekly.get(0).low()).isEqualByComparingTo("995");
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

		// 1d 시드만 있는 상태에서 1m을 요청하면(다른 interval 시드) 빈 배열이어야 한다 — interval별 맵이 서로 새지 않는다.
		List<CandleResponse> minute = service.getCandles(coinInstrument.getId(), "1m", null, null, null).content();
		assertThat(minute).isEmpty();
	}

	// 048 항목5(CANDLE-PAGE-006·007·019): 200의 배수(400개)로 거래일을 시드해, 1페이지·2페이지가 각각 정확히
	// 200개씩 나오고 3페이지째에 빈 content+hasNext=false가 나오는 것(CANDLE-PAGE-007)을 실 MySQL로 고정한다.
	// 두 페이지의 합집합에 중복·누락이 0건인지, 그리고 각 페이지의 실제 구간을 커서 없이 from~to로 직접 조회한
	// 결과(상한 200 안)와 값·순서가 일치하는지도 함께 확인한다.
	// @Transactional — saveInstrument가 새 Instrument 행을 커밋하면 InstrumentRepositoryTest의 정확한 개수 단정을
	// 깨뜨릴 수 있어(2026-07-30 agent-mistakes.md와 동일 패턴) 테스트 종료 시 자동 롤백시켜 격리한다.
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

		// 데이터 끝에서 같은 커서로 재요청해도 결과가 바뀌지 않는다 — 무한 루프 방지 확인.
		CandleListResponse page3Again = service.getCandles(instrument.getId(), "1d", null, null, page2.nextCursor());
		assertThat(page3Again.content()).isEmpty();
		assertThat(page3Again.hasNext()).isFalse();

		// 합집합 중복·누락 0건 — 두 페이지(과거→최신 순으로 이어 붙임)를 합치면 시드한 400개 거래일과 정확히 일치한다.
		List<LocalDateTime> combinedSourceTimes = new ArrayList<>();
		combinedSourceTimes.addAll(page2.content().stream().map(CandleResponse::sourceTime).toList());
		combinedSourceTimes.addAll(page1.content().stream().map(CandleResponse::sourceTime).toList());
		List<LocalDateTime> expectedSourceTimes = tradingDates.stream()
			.map(date -> LocalDateTime.of(date, LocalTime.MIDNIGHT))
			.toList();
		assertThat(combinedSourceTimes).containsExactlyElementsOf(expectedSourceTimes);
		assertThat(combinedSourceTimes).doesNotHaveDuplicates();

		// 집합·순서 일치 — 각 페이지의 실제 구간을 커서 없이 from~to로 직접 조회한 결과(상한 200 안)와 값·순서가 같다.
		LocalDateTime page1From = page1.content().get(0).sourceTime();
		LocalDateTime page1To = page1.content().get(page1.content().size() - 1).sourceTime();
		CandleListResponse directPage1 = service.getCandles(instrument.getId(), "1d", page1From, page1To, null);
		assertThat(directPage1.content()).containsExactlyElementsOf(page1.content());

		LocalDateTime page2From = page2.content().get(0).sourceTime();
		LocalDateTime page2To = page2.content().get(page2.content().size() - 1).sourceTime();
		CandleListResponse directPage2 = service.getCandles(instrument.getId(), "1d", page2From, page2To, null);
		assertThat(directPage2.content()).containsExactlyElementsOf(page2.content());
	}

	// 048 항목5: 1w도 같은 커서 계약을 따르는지 확인한다(CANDLE-PAGE-008 균질 계약). 250주(200의 배수가 아님)를
	// 시드해 1페이지(200개, hasNext=true) → 2페이지(50개, hasNext=false)로 자연스럽게 끝나는 경우를 검증한다.
	@Test
	@Transactional
	void weeklyIntervalCursorPaginationCoversAllWeeksWithoutDuplicationOrGaps() {
		Instrument instrument = saveInstrument("CDL0473B");
		LocalDate firstMonday = LocalDate.of(2015, 1, 5); // 월요일
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

	// 048 항목5: 1M도 같은 커서 계약을 따르는지 확인한다. 250개월을 시드해 1페이지(200개)→2페이지(50개, hasNext=false)로 끝난다.
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

	// CANDLE-PAGE-026: 주식 1m은 200개 봉이 모여도, 그리고 커서를 함께 줘도 hasNext는 항상 false·nextCursor는 항상
	// null이고 content는 커서 없는 요청과 완전히 동일한 단일 재생 거래일 구간이다 — CANDLE-PAGE-007의 유일한 예외.
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

	// 048 항목5(무커서 회귀) — 4개 interval(1m·1d·1w·1M) × 주식·코인 조합에서 커서 없는 요청의 값·개수·정렬이
	// 페이지네이션 도입 이전과 동일하고 차이가 content 포장뿐임을 고정한다. 8개 조합 모두 데이터가 200개 미만이라
	// hasNext=false·nextCursor=null도 함께 확인한다.
	@Test
	@Transactional
	void noCursorRequestsMatchPrePaginationValuesAcrossFourIntervalsAndBothMarkets() {
		// 주식 1m
		Instrument stock = saveInstrument("CDL0473E");
		saveCandle(stock, LocalTime.of(9, 0), "71100");
		saveCandle(stock, LocalTime.of(9, 1), "71200");
		saveCandle(stock, LocalTime.of(9, 2), "71300");
		LocalDate serviceDate = LocalDate.of(2026, 8, 11);
		saveReadySession(serviceDate);

		// 주식 1d·1w·1M — 서로 다른 3개 거래일(월요일 3개)에 걸친 소량 데이터.
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
		assertThat(stockWeekly.content()).hasSize(3); // td1·td2·td3가 각각 서로 다른 주(월요일)
		assertThat(stockWeekly.hasNext()).isFalse();
		assertThat(stockWeekly.nextCursor()).isNull();

		CandleListResponse stockMonthly = aggService.getCandles(stock.getId(), "1M", null, null, null);
		assertThat(stockMonthly.content()).hasSize(2); // td1·td2(6월) + td3(7월)
		assertThat(stockMonthly.hasNext()).isFalse();
		assertThat(stockMonthly.nextCursor()).isNull();

		// 코인 4개 interval — FakeCryptoCandleProvider 시드.
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
