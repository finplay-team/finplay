// 실제 MySQL 기사 픽스처로 NewsMatcher.matchCrypto의 근거창 경계(§C-2)와 공시 미매칭(§C-3)을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
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

// NewsMatcherCryptoTest는 리포지토리가 mock이라 "어떤 인자로 물었는가"까지만 볼 수 있다. 이 항목의 핵심인
// 근거창 양끝 포함 여부와 "공시는 매칭하지 않는다"(§C-3 "코인 | 공시 없음")는 실제 쿼리가 돌아야 드러나므로
// 여기서 실 컨테이너 픽스처로 본다 — mock으로 끝내지 않는다(ADR-0003).
//
// NewsMatcherMatchingWindowTest(주식)와 같은 형태다. NewsMatcher는 @Component이지만 슬라이스가 올리지
// 않으므로 직접 생성하고, 설정값만 §C-7 feedback.crypto 기본값으로 고정한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class NewsMatcherCryptoMatchingWindowTest {

	// §C-7 feedback.crypto 기본값
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
		// V7 시드와 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "CMATCH01", "테스트코인A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "CMATCH02", "테스트코인B", new BigDecimal("100"), 80000, true, LocalDateTime.now()));
		matcher = new NewsMatcher(
			marketNewsItemRepository,
			// 근거 매칭과 무관한 나머지 값은 §C-7 feedback.news 기본값을 그대로 둔다.
			new FeedbackNewsProperties(
				"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, SPEC_MAX_SOURCES_PER_CARD, 50, 30, 30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, SPEC_MATCH_BEFORE_MINUTES),
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

	// --- 근거창 경계 (§C-2, 양끝 포함) — 뮤테이션 대상 ---

	// 하한 정각(occurredAt - 35분)과 상한 정각(occurredAt)은 포함하고, 하한 1분 전과 상한 1분 후는 제외한다.
	// >=/> 또는 <=/< 하나만 바뀌어도(오프바이원) 이 네 건 중 경계 쪽 둘의 포함 여부가 뒤집힌다.
	@Test
	@DisplayName("코인 근거창은 occurredAt-35분·occurredAt 정각을 포함하고 그 밖 1분은 제외한다")
	void matchCryptoWindowIncludesBothBoundaryMinutesAndExcludesTheMinutesOutside() {
		news("경계 밖 이전", OCCURRED_AT.minusMinutes(36));
		news("하한 정각", OCCURRED_AT.minusMinutes(35));
		news("상한 정각", OCCURRED_AT);
		news("경계 밖 이후", OCCURRED_AT.plusMinutes(1));

		assertThat(matchedTitles()).containsExactly("상한 정각", "하한 정각");
	}

	// 이후 방향이 조금이라도 열리면(§C-2 "이후는 0") occurredAt보다 늦게 발행된 기사가 새어 들어온다 —
	// 위 경계 테스트의 "경계 밖 이후"가 이미 이를 막지만, 여기서는 근거창 이후에만 기사가 있는 경우를 별도로 본다.
	@Test
	@DisplayName("occurredAt 이후에만 기사가 있으면 빈 목록이다")
	void matchCryptoReturnsEmptyWhenEveryArticleIsAfterOccurredAt() {
		news("occurredAt+1분", OCCURRED_AT.plusMinutes(1));
		news("occurredAt+10분", OCCURRED_AT.plusMinutes(10));

		assertThat(matchedTitles()).isEmpty();
	}

	// --- 공시 미매칭 (§C-3 "코인 | 공시 없음") — 뮤테이션 대상 ---

	// published_at을 근거창 안으로 조작한 공시를 심어 "종류로 막는다"를 확인한다 — 시각만으로는 우연히
	// 통과할 수 있어(공시는 접수일자 00:00:00이라 원래도 안 걸릴 수 있다) 종류 필터가 실제로 동작하는지를
	// 직접 본다.
	@Test
	@DisplayName("코인 근거창 안에 있는 공시조차 붙이지 않는다")
	void matchCryptoNeverMatchesDisclosureEvenWhenItsPublishedAtSitsInsideTheWindow() {
		disclosure("근거창 안 공시", OCCURRED_AT.minusMinutes(5));
		news("근거창 안 뉴스", OCCURRED_AT.minusMinutes(3));

		// 픽스처 전제 확인 — 종류를 안 거르면 실제로 잡히는 자리다.
		List<MarketNewsItem> withoutTypeFilter = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.DISCLOSURE,
				OCCURRED_AT.minusMinutes(SPEC_MATCH_BEFORE_MINUTES),
				OCCURRED_AT);
		assertThat(withoutTypeFilter).extracting(MarketNewsItem::getTitle).containsExactly("근거창 안 공시");

		assertThat(matchedTitles()).containsExactly("근거창 안 뉴스");
	}

	// --- 절단·종목 격리 ---

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
