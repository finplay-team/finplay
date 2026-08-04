// 실제 MySQL 분봉으로 getFullDayCandles·getPreviousTradingDayClose가 재생 노출 게이트와 무관하게 동작하는지 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// spec 012 §C-6이 신설을 지시한 조회 4종 중 분봉을 실제로 읽는 둘을 실 DB로 본다. StockReplayServiceTest는
// 전부 mock이라 "무슨 날짜·무슨 상태를 보는가"까지만 단정할 수 있고, 이 항목의 핵심인
// "getRevealedCandles를 그대로 쓰면 배치가 예외 없이 매일 0건이 된다"는 두 메서드를 같은 픽스처에
// 나란히 세워야 드러난다 — 그래서 mock으로 끝내지 않는다(ADR-0003).
//
// StockReplayService는 @Service이지만 여기서는 슬라이스가 올리지 않으므로 직접 생성한다. 리포지토리 두
// 개는 실 컨테이너에 붙은 진짜 빈이고, Clock만 테스트가 고정한다. @DataJpaTest가 이미 트랜잭션 안이라
// 서비스의 @Transactional(readOnly = true)이 프록시 없이도 같은 조건이다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class StockReplayServiceFullDayQueryTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 2026-07-28(화)에 2026-07-27(월)을 재생한다.
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 28);
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 27);
	// SOURCE_TRADING_DATE(월)의 직전 영업일 — 주말 2일을 건너뛴 금요일이다.
	private static final LocalDate PREVIOUS_BUSINESS_DAY = LocalDate.of(2026, 7, 24);
	// 그보다 하루 더 앞선 영업일(목). "가장 최근 분봉"으로 거슬러 올라가면 여기까지 잡힌다.
	private static final LocalDate TWO_BUSINESS_DAYS_BEFORE = LocalDate.of(2026, 7, 23);

	// 그날 마지막 분봉을 15:30이 아닌 시각에 둔다 — 리터럴 15:30으로 찾는 구현이면 여기서 빈 결과가 된다(§C-2-1).
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "TEST180", "테스트종목180", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
	}

	private StockReplayService service(LocalDate serviceDate, LocalTime time) {
		Clock clock = Clock.fixed(LocalDateTime.of(serviceDate, time).atZone(KST).toInstant(), KST);
		return new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, clock, new BusinessDayCalendar());
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

	// 원본 거래일 하루치 — 09:00~09:02, 10:00, 그리고 15:30이 아닌 마지막 분봉 15:27.
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

	// 이 항목에서 가장 중요한 테스트다. 개장 전 배치(§C-1의 08:45 전후)가 기존 getRevealedCandles를 쓰면
	// resolveRevealCutoff가 비어 빈 목록을 받는다 — 예외도 로그도 없이 카드가 매일 0건이 된다.
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

	// 장중에도 두 메서드는 갈린다 — 재생 시각 이후 분봉을 getRevealedCandles는 감추고 getFullDayCandles는 준다.
	// getFullDayCandles를 사용자 응답에 그대로 실으면 그날 오후가 오전에 새어 나간다는 뜻이기도 하다.
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

	// ① 직전 영업일에 분봉이 있으면 그날 "마지막" 분봉의 close다. 마지막 분봉을 15:27에 두었으므로
	// 리터럴 15:30을 찾는 구현은 여기서 empty가 되어 실패한다(§C-2-1).
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

	// ② 직전 영업일에 분봉이 없으면 empty다 — 오류가 아니다(§FEED-002).
	@Test
	@DisplayName("직전 거래일에 분봉이 없으면 예외 없이 empty다")
	void getPreviousTradingDayCloseReturnsEmptyWhenPreviousBusinessDayHasNoCandle() {
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE)).isEmpty();
	}

	// ③ 그 앞 영업일에만 있어도 empty다 — "그 종목의 가장 최근 분봉"으로 거슬러 올라가면 며칠 전 종가를
	// 직전 종가로 읽어 있지도 않은 큰 갭 카드를 만든다(§C-6).
	@Test
	@DisplayName("직전 거래일이 비고 그 앞 영업일에만 분봉이 있어도 거슬러 올라가지 않는다")
	void getPreviousTradingDayCloseDoesNotFallBackToAnEarlierBusinessDay() {
		saveCandle(TWO_BUSINESS_DAYS_BEFORE, LocalTime.of(9, 0), "60000");
		saveCandle(TWO_BUSINESS_DAYS_BEFORE, LAST_CANDLE_TIME, "61000");
		saveFullDayCandles();
		StockReplayService service = service(SERVICE_DATE, LocalTime.of(8, 45));

		assertThat(service.getPreviousTradingDayClose(instrument.getId(), SOURCE_TRADING_DATE)).isEmpty();
	}

	// 두 메서드 모두 재생 시각과 무관하다 — 배치가 도는 개장 전이든 장중이든 같은 값이어야 한다.
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

	// 실 DB 행에서 DTO까지 이어지는 경로를 한 번 확인한다 — mock 테스트는 엔티티를 직접 만들어 넣는다.
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
