package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
class NewsMatcherMatchingWindowTest {

	private static final int SPEC_MATCH_BEFORE_MINUTES = 30;

	private static final int SPEC_MATCH_AFTER_MINUTES = 5;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 28);

	private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);

	private static final LocalDate FRIDAY_BEFORE_MONDAY = LocalDate.of(2026, 7, 24);

	private static final LocalTime PRE_MARKET_FROM = LocalTime.of(15, 30);

	private static final LocalTime PRE_MARKET_TO = LocalTime.of(9, 0);

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private NewsMatcher matcher;

	private Instrument instrumentA;

	private Instrument instrumentB;

	@BeforeEach
	void setUp() {
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MATCH01", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MATCH02", "테스트종목B", new BigDecimal("100"), 80000, true, LocalDateTime.now()));
		matcher = new NewsMatcher(
			marketNewsItemRepository,
			new FeedbackNewsProperties(
				"0 0/30 * * * *",
				"0 0/30 8-20 * * MON-FRI",
				SPEC_MATCH_BEFORE_MINUTES,
				SPEC_MATCH_AFTER_MINUTES,
				SPEC_MAX_SOURCES_PER_CARD,
				50,
				30,
				30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, 30),
			new BusinessDayCalendar());
	}

	private void save(Instrument instrument, MarketNewsItemType type, String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			type,
			title,
			"테스트경제",
			"https://news.example.com/" + instrument.getSymbol() + "/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	private void news(String title, LocalDateTime publishedAt) {
		save(instrumentA, MarketNewsItemType.NEWS, title, publishedAt);
	}

	private void disclosure(String title, LocalDateTime publishedAt) {
		save(instrumentA, MarketNewsItemType.DISCLOSURE, title, publishedAt);
	}

	private static PriceMoveDetectionDto intraday(LocalTime windowEnd) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY,
			windowEnd.minusMinutes(5),
			windowEnd,
			new BigDecimal("0.020000"),
			new BigDecimal("3.0000"));
	}

	private static PriceMoveDetectionDto openingGap() {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			LocalTime.of(9, 0),
			LocalTime.of(9, 0),
			new BigDecimal("0.030000"),
			new BigDecimal("3.0000"));
	}

	private List<String> matchedTitles(LocalDate originTradeDate, PriceMoveDetectionDto detection) {
		return matcher.match(instrumentA.getId(), originTradeDate, detection).stream()
			.map(MarketNewsItem::getTitle)
			.toList();
	}


	@Test
	@DisplayName("시가 갭 근거는 rcept_dt=D-1 공시만 붙이고 rcept_dt=D 공시는 붙이지 않는다")
	void openingGapTakesOnlyTheDisclosureReceivedOnThePreviousTradingDate() {
		disclosure("D-1 접수 공시", MONDAY.atStartOfDay());
		disclosure("D 접수 공시", TUESDAY.atStartOfDay());
		news("전장 뉴스", LocalDateTime.of(MONDAY, LocalTime.of(18, 0)));

		List<String> matched = matchedTitles(TUESDAY, openingGap());

		assertThat(matched).containsExactly("전장 뉴스", "D-1 접수 공시");
		assertThat(matched).doesNotContain("D 접수 공시");
	}

	@Test
	@DisplayName("같은 두 공시에 전장 datetime 구간을 걸면 정확히 반대 결과가 나온다")
	void aSingleDatetimeRangeOverThePreMarketWindowSelectsExactlyTheWrongDisclosure() {
		disclosure("D-1 접수 공시", MONDAY.atStartOfDay());
		disclosure("D 접수 공시", TUESDAY.atStartOfDay());

		List<MarketNewsItem> byDatetimeRange = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.DISCLOSURE,
				LocalDateTime.of(MONDAY, PRE_MARKET_FROM),
				LocalDateTime.of(TUESDAY, PRE_MARKET_TO));

		assertThat(byDatetimeRange).extracting(MarketNewsItem::getTitle).containsExactly("D 접수 공시");
		assertThat(matchedTitles(TUESDAY, openingGap())).containsExactly("D-1 접수 공시");
	}

	@Test
	@DisplayName("장중 카드는 근거창 안에 있는 공시조차 붙이지 않는다")
	void intradayNeverMatchesDisclosureEvenWhenItsPublishedAtSitsInsideTheWindow() {
		disclosure("근거창 안 공시", LocalDateTime.of(TUESDAY, LocalTime.of(9, 58)));
		news("근거창 안 뉴스", LocalDateTime.of(TUESDAY, LocalTime.of(9, 59)));

		List<MarketNewsItem> withoutTypeFilter = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.DISCLOSURE,
				LocalDateTime.of(TUESDAY, LocalTime.of(9, 30)),
				LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)));
		assertThat(withoutTypeFilter).extracting(MarketNewsItem::getTitle).containsExactly("근거창 안 공시");

		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0)))).containsExactly("근거창 안 뉴스");
	}


	@Test
	@DisplayName("장중 근거창은 -30분·+5분 정각을 포함하고 -31분·+6분은 제외한다")
	void intradayWindowIncludesBothBoundaryMinutesAndExcludesTheMinutesOutside() {
		news("경계 밖 09:29", LocalDateTime.of(TUESDAY, LocalTime.of(9, 29)));
		news("하한 정각 09:30", LocalDateTime.of(TUESDAY, LocalTime.of(9, 30)));
		news("상한 정각 10:05", LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)));
		news("경계 밖 10:06", LocalDateTime.of(TUESDAY, LocalTime.of(10, 6)));

		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0))))
			.containsExactly("상한 정각 10:05", "하한 정각 09:30");
	}

	@Test
	@DisplayName("원본 거래일이 월요일이면 전장 하한이 금요일 15:30이라 주말 기사가 전부 들어온다")
	void openingGapOnMondayUsesFridayFifteenThirtyAsTheLowerBound() {
		news("금 15:29", LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(15, 29)));
		news("금 15:30 정각", LocalDateTime.of(FRIDAY_BEFORE_MONDAY, PRE_MARKET_FROM));
		news("금 저녁", LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(18, 0)));
		news("토 오후", LocalDateTime.of(2026, 7, 25, 14, 0));
		news("일 저녁", LocalDateTime.of(2026, 7, 26, 20, 0));
		news("월 09:00 정각", LocalDateTime.of(MONDAY, PRE_MARKET_TO));
		news("월 09:01", LocalDateTime.of(MONDAY, LocalTime.of(9, 1)));

		assertThat(matchedTitles(MONDAY, openingGap()))
			.containsExactly("월 09:00 정각", "일 저녁", "토 오후", "금 저녁", "금 15:30 정각");
	}


	@Test
	@DisplayName("근거가 상한을 넘으면 이벤트에 가까운 순으로 5건만 남는다")
	void truncatesToMaxSourcesPerCardByDistanceFromEvent() {
		news("09:30", LocalDateTime.of(TUESDAY, LocalTime.of(9, 30)));
		news("09:40", LocalDateTime.of(TUESDAY, LocalTime.of(9, 40)));
		news("09:53", LocalDateTime.of(TUESDAY, LocalTime.of(9, 53)));
		news("09:56", LocalDateTime.of(TUESDAY, LocalTime.of(9, 56)));
		news("09:58", LocalDateTime.of(TUESDAY, LocalTime.of(9, 58)));
		news("10:03", LocalDateTime.of(TUESDAY, LocalTime.of(10, 3)));
		news("10:05", LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)));

		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0))))
			.containsExactly("09:58", "10:03", "09:56", "10:05", "09:53");
	}

	@Test
	@DisplayName("근거창 밖에만 기사가 있으면 장중·시가 갭 모두 빈 목록이다")
	void returnsEmptyWhenEveryArticleIsOutsideTheWindow() {
		news("근거창 이전", LocalDateTime.of(TUESDAY, LocalTime.of(9, 29)));
		news("근거창 이후", LocalDateTime.of(TUESDAY, LocalTime.of(10, 6)));
		disclosure("D 접수 공시", TUESDAY.atStartOfDay());

		assertThat(matcher.match(instrumentA.getId(), TUESDAY, intraday(LocalTime.of(10, 0)))).isEmpty();
		assertThat(matcher.match(instrumentA.getId(), TUESDAY, openingGap())).isEmpty();
	}

	@Test
	@DisplayName("다른 종목의 기사·공시는 근거창 안에 있어도 새어 들어오지 않는다")
	void doesNotLeakArticlesOfAnotherInstrument() {
		save(instrumentB, MarketNewsItemType.NEWS, "B 종목 뉴스",
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 59)));
		save(instrumentB, MarketNewsItemType.DISCLOSURE, "B 종목 공시",
			MONDAY.atStartOfDay());
		news("A 종목 뉴스", LocalDateTime.of(TUESDAY, LocalTime.of(9, 57)));

		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0)))).containsExactly("A 종목 뉴스");
		assertThat(matchedTitles(TUESDAY, openingGap())).isEmpty();
	}
}
