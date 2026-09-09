package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
class NewsMatcherCryptoMatchingWindowTest {

	private static final int SPEC_MATCH_BEFORE_MINUTES = 35;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 28, 10, 0);

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
			Market.CRYPTO, "CMATCH01", "테스트코인A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "CMATCH02", "테스트코인B", new BigDecimal("100"), 80000, true, LocalDateTime.now()));
		matcher = new NewsMatcher(
			marketNewsItemRepository,
			new FeedbackNewsProperties(
				"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, SPEC_MAX_SOURCES_PER_CARD, 50, 30, 30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, SPEC_MATCH_BEFORE_MINUTES, 30),
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

	private List<String> matchedTitles() {
		return matcher.matchCrypto(instrumentA.getId(), OCCURRED_AT).stream()
			.map(MarketNewsItem::getTitle)
			.toList();
	}


	@Test
	@DisplayName("코인 근거창은 occurredAt-35분·occurredAt 정각을 포함하고 그 밖 1분은 제외한다")
	void matchCryptoWindowIncludesBothBoundaryMinutesAndExcludesTheMinutesOutside() {
		news("경계 밖 이전", OCCURRED_AT.minusMinutes(36));
		news("하한 정각", OCCURRED_AT.minusMinutes(35));
		news("상한 정각", OCCURRED_AT);
		news("경계 밖 이후", OCCURRED_AT.plusMinutes(1));

		assertThat(matchedTitles()).containsExactly("상한 정각", "하한 정각");
	}

	@Test
	@DisplayName("occurredAt 이후에만 기사가 있으면 빈 목록이다")
	void matchCryptoReturnsEmptyWhenEveryArticleIsAfterOccurredAt() {
		news("occurredAt+1분", OCCURRED_AT.plusMinutes(1));
		news("occurredAt+10분", OCCURRED_AT.plusMinutes(10));

		assertThat(matchedTitles()).isEmpty();
	}


	@Test
	@DisplayName("코인 근거창 안에 있는 공시조차 붙이지 않는다")
	void matchCryptoNeverMatchesDisclosureEvenWhenItsPublishedAtSitsInsideTheWindow() {
		disclosure("근거창 안 공시", OCCURRED_AT.minusMinutes(5));
		news("근거창 안 뉴스", OCCURRED_AT.minusMinutes(3));

		List<MarketNewsItem> withoutTypeFilter = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.DISCLOSURE,
				OCCURRED_AT.minusMinutes(SPEC_MATCH_BEFORE_MINUTES),
				OCCURRED_AT);
		assertThat(withoutTypeFilter).extracting(MarketNewsItem::getTitle).containsExactly("근거창 안 공시");

		assertThat(matchedTitles()).containsExactly("근거창 안 뉴스");
	}


	@Test
	@DisplayName("근거가 상한을 넘으면 occurredAt에 가까운 순으로 5건만 남는다")
	void matchCryptoTruncatesToMaxSourcesPerCardByDistanceFromOccurredAt() {
		news("30분전", OCCURRED_AT.minusMinutes(30));
		news("20분전", OCCURRED_AT.minusMinutes(20));
		news("7분전", OCCURRED_AT.minusMinutes(7));
		news("4분전", OCCURRED_AT.minusMinutes(4));
		news("2분전", OCCURRED_AT.minusMinutes(2));
		news("3분전", OCCURRED_AT.minusMinutes(3));
		news("5분전", OCCURRED_AT.minusMinutes(5));

		assertThat(matchedTitles())
			.containsExactly("2분전", "3분전", "4분전", "5분전", "7분전");
	}

	@Test
	@DisplayName("다른 종목의 기사·공시는 근거창 안에 있어도 새어 들어오지 않는다")
	void matchCryptoDoesNotLeakArticlesOfAnotherInstrument() {
		save(instrumentB, MarketNewsItemType.NEWS, "B 종목 뉴스", OCCURRED_AT.minusMinutes(1));
		save(instrumentB, MarketNewsItemType.DISCLOSURE, "B 종목 공시", OCCURRED_AT.minusMinutes(1));
		news("A 종목 뉴스", OCCURRED_AT.minusMinutes(2));

		assertThat(matchedTitles()).containsExactly("A 종목 뉴스");
	}
}
