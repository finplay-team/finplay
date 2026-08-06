// 실제 MySQL 기사 픽스처로 NewsMatcher의 근거창 경계와 spec 012 §C-3 공시 날짜 판정을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
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

// NewsMatcherTest는 리포지토리가 mock이라 "어떤 인자로 물었는가"까지만 볼 수 있다. 이 항목의 핵심인
// §C-3(공시를 datetime 구간에 태우면 정확히 반대로 걸린다)과 근거창의 양끝 포함 여부는 실제 쿼리가
// 돌아야 드러나므로 여기서 실 컨테이너 픽스처로 본다 — mock으로 끝내지 않는다(ADR-0003).
//
// NewsMatcher는 @Component이지만 슬라이스가 올리지 않으므로 직접 생성한다. 리포지토리는 실 컨테이너에
// 붙은 진짜 빈이고, 설정값만 §C-7 기본값으로 고정한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class NewsMatcherMatchingWindowTest {

	// §C-7 feedback.news 기본값
	private static final int SPEC_MATCH_BEFORE_MINUTES = 30;

	private static final int SPEC_MATCH_AFTER_MINUTES = 5;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	// 2026-07-28(화) — 직전 영업일이 바로 전날인 단순 케이스. 공시 날짜 판정(§C-3)은 이 쌍으로 본다.
	private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 28);

	// 2026-07-27(월). TUESDAY의 직전 영업일이면서, 그 자체를 원본 거래일로 쓰면 직전 영업일이
	// 주말을 건너뛴 금요일이 되는 날이다 — 두 역할로 쓴다.
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
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MATCH01", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MATCH02", "테스트종목B", new BigDecimal("100"), 80000, true, LocalDateTime.now()));
		matcher = new NewsMatcher(
			marketNewsItemRepository,
			// 뒤 세 값은 §C-7의 목록 상한 3종이다 — 근거 매칭과 무관해 이 테스트는 쓰지 않는다.
			new FeedbackNewsProperties(
				"0 0/30 * * * *",
				"0 0/30 8-20 * * MON-FRI",
				SPEC_MATCH_BEFORE_MINUTES,
				SPEC_MATCH_AFTER_MINUTES,
				SPEC_MAX_SOURCES_PER_CARD,
				50,
				30,
				30),
			// 주식 근거 매칭과 무관해 §C-7 기본값을 그대로 둔다.
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

	// --- §C-3 공시 날짜 판정 (이 파일에서 가장 중요) ---

	// 두 건을 함께 심어야 회귀가 잡힌다. 한쪽만 심으면 어느 구현이든 통과한다.
	@Test
	@DisplayName("시가 갭 근거는 rcept_dt=D-1 공시만 붙이고 rcept_dt=D 공시는 붙이지 않는다")
	void openingGapTakesOnlyTheDisclosureReceivedOnThePreviousTradingDate() {
		disclosure("D-1 접수 공시", MONDAY.atStartOfDay());
		disclosure("D 접수 공시", TUESDAY.atStartOfDay());
		news("전장 뉴스", LocalDateTime.of(MONDAY, LocalTime.of(18, 0)));

		List<String> matched = matchedTitles(TUESDAY, openingGap());

		// 이벤트(D 09:00)와 가까운 순 — 전장 뉴스(15시간) → D-1 접수 공시(33시간)
		assertThat(matched).containsExactly("전장 뉴스", "D-1 접수 공시");
		assertThat(matched).doesNotContain("D 접수 공시");
	}

	// 위 테스트의 반대편을 실제 쿼리로 보여 준다. 같은 두 행에 전장 datetime 구간을 그대로 걸면
	// 결과가 정확히 뒤집힌다 — D 접수분만 잡히고 D-1 접수분은 빠진다. 매처가 날짜 질의를 따로 쓰는
	// 이유가 이것이고, 이 단정이 깨지면 §C-3의 전제가 바뀐 것이다.
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

	// FEED-003 — 시각으로만 걸러도 우연히 통과하므로(공시는 00:00:00이라 장중 근거창에 원래 안 들어온다)
	// published_at을 근거창 안으로 조작한 공시를 심어 "종류로 막는다"를 확인한다.
	@Test
	@DisplayName("장중 카드는 근거창 안에 있는 공시조차 붙이지 않는다")
	void intradayNeverMatchesDisclosureEvenWhenItsPublishedAtSitsInsideTheWindow() {
		disclosure("근거창 안 공시", LocalDateTime.of(TUESDAY, LocalTime.of(9, 58)));
		news("근거창 안 뉴스", LocalDateTime.of(TUESDAY, LocalTime.of(9, 59)));

		// 픽스처 전제 확인 — 종류를 안 거르면 실제로 둘 다 잡히는 자리다.
		List<MarketNewsItem> withoutTypeFilter = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.DISCLOSURE,
				LocalDateTime.of(TUESDAY, LocalTime.of(9, 30)),
				LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)));
		assertThat(withoutTypeFilter).extracting(MarketNewsItem::getTitle).containsExactly("근거창 안 공시");

		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0)))).containsExactly("근거창 안 뉴스");
	}

	// --- 근거창 경계 (§C-2, 양끝 포함) ---

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

	// 전장 하한의 D-1은 BusinessDayCalendar.previousBusinessDay다. D.minusDays(1)로 짜면 토요일 15:30이
	// 하한이 되어 금요일 저녁~토요일 오후 기사가 통째로 빠진다 — 아래 다섯 건 중 셋을 잃는다.
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

		// 이벤트(월 09:00)와 가까운 순. 상한 5건과 정확히 같아 절단은 일어나지 않는다.
		assertThat(matchedTitles(MONDAY, openingGap()))
			.containsExactly("월 09:00 정각", "일 저녁", "토 오후", "금 저녁", "금 15:30 정각");
	}

	// --- 절단·빈 결과·종목 격리 ---

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

		// windowEnd 앞뒤 거리가 비대칭이라 단순 발행시각 정렬과 결과가 갈린다 —
		// 오름차순이면 남는 집합 자체가 다르고, 내림차순이면 집합은 같아도 순서가 다르다.
		assertThat(matchedTitles(TUESDAY, intraday(LocalTime.of(10, 0))))
			.containsExactly("09:58", "10:03", "09:56", "10:05", "09:53");
	}

	// 탐지 ⑥의 매처 쪽 선확인 — 근거창 밖에만 기사가 있으면 빈 목록이고 예외가 없다.
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
