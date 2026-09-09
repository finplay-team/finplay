package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class StockReplayServiceFullDayQueryTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 28);
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 27);
	private static final LocalDate PREVIOUS_BUSINESS_DAY = LocalDate.of(2026, 7, 24);
	private static final LocalDate TWO_BUSINESS_DAYS_BEFORE = LocalDate.of(2026, 7, 23);

	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "TEST180", "테스트종목180", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
	}

	private StockReplayService service(LocalDate serviceDate, LocalTime time) {
		Clock clock = Clock.fixed(LocalDateTime.of(serviceDate, time).atZone(KST).toInstant(), KST);
		return new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, stockDailyCandleRepository, clock,
			new BusinessDayCalendar());
	}

	private void saveCandle(LocalDate tradingDate, LocalTime candleTime, String close) {
		stockCandleRepository.save(StockCandle.create(
			instrument,
			tradingDate,
			candleTime,
			new BigDecimal("71000"),
			new BigDecimal("71500"),
			new BigDecimal("70900"),
			new BigDecimal(close),
			123456L,
			"KRX_REPLAY",
			LocalDateTime.now()));
	}

	private void saveFullDayCandles() {
		saveCandle(SOURCE_TRADING_DATE, LocalTime.of(9, 0), "71000");
		saveCandle(SOURCE_TRADING_DATE, LocalTime.of(9, 1), "71100");
		saveCandle(SOURCE_TRADING_DATE, LocalTime.of(9, 2), "71200");
		saveCandle(SOURCE_TRADING_DATE, LocalTime.of(10, 0), "72000");
		saveCandle(SOURCE_TRADING_DATE, LAST_CANDLE_TIME, "73000");
	}

	private void saveReadySession() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			SERVICE_DATE,
			SOURCE_TRADING_DATE,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 30)),
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
	}

	@Test
	@DisplayName("개장 전 시각에 getRevealedCandles는 빈 목록이고 getFullDayCandles는 하루치 전건이다")
	void getFullDayCandlesReturnsWholeDayWhileGetRevealedCandlesIsEmptyBeforeMarketOpen() {
		saveReadySession();
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		List<StockCandleDto> revealed = service.getRevealedCandles(instrument.getId(), null, null);
		List<StockCandleDto> fullDay = service.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE);

		assertThat(revealed).isEmpty();
		assertThat(fullDay)
			.extracting(StockCandleDto::candleTime)
			.containsExactly(
				LocalTime.of(9, 0),
				LocalTime.of(9, 1),
				LocalTime.of(9, 2),
				LocalTime.of(10, 0),
				LAST_CANDLE_TIME);
	}

	@Test
	@DisplayName("장중 시각에 getRevealedCandles는 컷오프까지만 주고 getFullDayCandles는 오후 분봉까지 준다")
	void getFullDayCandlesIncludesAfternoonCandlesThatGetRevealedCandlesStillHidesDuringSession() {
		saveReadySession();
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(10, 30));

		List<StockCandleDto> revealed = service.getRevealedCandles(instrument.getId(), null, null);
		List<StockCandleDto> fullDay = service.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE);

		assertThat(revealed)
			.extracting(StockCandleDto::candleTime)
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 1), LocalTime.of(9, 2), LocalTime.of(10, 0));
		assertThat(fullDay).hasSize(5);
		assertThat(fullDay).extracting(StockCandleDto::candleTime).contains(LAST_CANDLE_TIME);
	}

	@Test
	@DisplayName("재생세션 행이 아예 없어도 getFullDayCandles는 하루치를 그대로 준다")
	void getFullDayCandlesIgnoresMissingReplaySession() {
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getRevealedCandles(instrument.getId(), null, null)).isEmpty();
		assertThat(service.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE)).hasSize(5);
	}

	@Test
	@DisplayName("재생세션이 PREPARING이어도 getFullDayCandles는 하루치를 그대로 준다")
	void getFullDayCandlesIgnoresPreparingReplaySession() {
		stockReplaySessionRepository.save(StockReplaySession.preparing(
			SERVICE_DATE, SOURCE_TRADING_DATE, LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(10, 30));

		assertThat(service.getRevealedCandles(instrument.getId(), null, null)).isEmpty();
		assertThat(service.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE)).hasSize(5);
	}

	@Test
	@DisplayName("그 거래일에 분봉이 없으면 getFullDayCandles는 예외 없이 빈 목록이다")
	void getFullDayCandlesReturnsEmptyWhenThatTradingDateHasNoCandle() {
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getFullDayCandles(instrument.getId(), PREVIOUS_BUSINESS_DAY)).isEmpty();
	}

	@Test
	@DisplayName("직전 거래일 마지막 분봉이 15:30이 아니어도 그 분봉의 종가를 돌려준다")
	void getPreviousTradingDayCloseReturnsCloseOfTheLastCandleEvenWhenItIsNotAtHalfPastThree() {
		saveCandle(PREVIOUS_BUSINESS_DAY, LocalTime.of(9, 0), "70000");
		saveCandle(PREVIOUS_BUSINESS_DAY, LocalTime.of(12, 0), "70500");
		saveCandle(PREVIOUS_BUSINESS_DAY, LAST_CANDLE_TIME, "70800");
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE))
			.hasValueSatisfying(close -> assertThat(close).isEqualByComparingTo("70800"));
	}

	@Test
	@DisplayName("직전 거래일에 분봉이 없으면 예외 없이 empty다")
	void getPreviousTradingDayCloseReturnsEmptyWhenPreviousBusinessDayHasNoCandle() {
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE)).isEmpty();
	}

	@Test
	@DisplayName("직전 거래일이 비고 그 앞 영업일에만 분봉이 있어도 거슬러 올라가지 않는다")
	void getPreviousTradingDayCloseDoesNotFallBackToAnEarlierBusinessDay() {
		saveCandle(TWO_BUSINESS_DAYS_BEFORE, LocalTime.of(9, 0), "60000");
		saveCandle(TWO_BUSINESS_DAYS_BEFORE, LAST_CANDLE_TIME, "61000");
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE)).isEmpty();
	}

	@Test
	@DisplayName("getFullDayCandles·getPreviousTradingDayClose는 재생 시각이 달라도 같은 값을 준다")
	void bothGateBypassingQueriesAreIndependentOfTheReplayClock() {
		saveReadySession();
		saveFullDayCandles();
		saveCandle(PREVIOUS_BUSINESS_DAY, LAST_CANDLE_TIME, "70800");

		StockReplayService beforeOpen = service(SERVICE_DATE, LocalTime.of(8, 45));
		StockReplayService duringSession = service(SERVICE_DATE, LocalTime.of(10, 30));

		assertThat(beforeOpen.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE))
			.isEqualTo(duringSession.getFullDayCandles(instrument.getId(), SOURCE_TRADING_DATE));
		assertThat(beforeOpen.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE))
			.isEqualTo(duringSession.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE));
	}

	@Test
	@DisplayName("실제 READY 행에서 getCurrentReplaySession·getSourceTradingDate가 원본 거래일을 읽는다")
	void readsSourceTradingDateFromRealReadySessionRow() {
		saveReadySession();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getCurrentReplaySession())
			.isEqualTo(new StockReplaySessionDto(true, SOURCE_TRADING_DATE));
		assertThat(service.getSourceTradingDate(SERVICE_DATE)).contains(SOURCE_TRADING_DATE);
	}
}
