package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NewsItemTruncatorTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDateTime DISCLOSURE_AT = PREVIOUS_TRADE_DATE.atStartOfDay();

	private static final Instrument INSTRUMENT = Instrument.create(
		Market.STOCK, "TRUNC1", "테스트종목", BigDecimal.ONE, 10000L, true, LocalDateTime.now());

	private static MarketNewsItem item(long id, MarketNewsItemType type, LocalDateTime publishedAt) {
		MarketNewsItem news = MarketNewsItem.create(
			INSTRUMENT,
			type,
			type + "-" + id,
			"테스트경제",
			"https://news.example.test/" + id,
			publishedAt,
			publishedAt);
		ReflectionTestUtils.setField(news, "id", id);
		return news;
	}

	private static MarketNewsItem news(long id, LocalTime publishedAt) {
		return item(id, MarketNewsItemType.NEWS, LocalDateTime.of(PREVIOUS_TRADE_DATE, publishedAt));
	}

	private static MarketNewsItem disclosure(long id) {
		return item(id, MarketNewsItemType.DISCLOSURE, DISCLOSURE_AT);
	}

	private static List<MarketNewsItem> tenNewsAndTwoDisclosures() {
		List<MarketNewsItem> candidates = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			candidates.add(news(index + 1L, LocalTime.of(16, 0).plusMinutes(index * 10L)));
		}
		candidates.add(disclosure(101L));
		candidates.add(disclosure(102L));
		Collections.shuffle(candidates, new java.util.Random(42));
		return candidates;
	}

	@Test
	@DisplayName("상한을 넘으면 공시 2건이 전부 살아남고 남은 자리를 뉴스 최신순이 채운다")
	void keepsEveryDisclosureAndFillsTheRestWithTheNewestNewsWhenOverTheLimit() {
		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(tenNewsAndTwoDisclosures(), 5);

		assertThat(selected).hasSize(5);
		assertThat(selected)
			.filteredOn(each -> each.getType() == MarketNewsItemType.DISCLOSURE)
			.as("공시는 목록 최하위라 규칙이 없으면 상한을 넘는 순간 항상 먼저 잘린다")
			.extracting(MarketNewsItem::getId)
			.containsExactlyInAnyOrder(101L, 102L);
		assertThat(selected)
			.filteredOn(each -> each.getType() == MarketNewsItemType.NEWS)
			.extracting(MarketNewsItem::getId)
			.containsExactly(10L, 9L, 8L);
	}

	@Test
	@DisplayName("절단 뒤 순서는 발행시각 내림차순 그대로다 — 공시가 목록 위로 올라오지 않는다")
	void ordersSurvivorsByPublishedAtDescendingSoDisclosuresStayAtTheBottom() {
		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(tenNewsAndTwoDisclosures(), 5);

		assertThat(selected).extracting(MarketNewsItem::getId).containsExactly(10L, 9L, 8L, 102L, 101L);
		assertThat(selected).extracting(MarketNewsItem::getPublishedAt).isSortedAccordingTo(
			java.util.Comparator.reverseOrder());
	}

	@Test
	@DisplayName("발행시각이 같으면 id 내림차순이다")
	void breaksPublishedAtTiesByIdDescending() {
		List<MarketNewsItem> tied = List.of(
			disclosure(7L), disclosure(9L), disclosure(8L));

		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(tied, 10);

		assertThat(selected).extracting(MarketNewsItem::getId).containsExactly(9L, 8L, 7L);
	}

	@Test
	@DisplayName("상한 이하이면 전부 남기고 정렬만 한다")
	void keepsEverythingAndOnlySortsWhenWithinTheLimit() {
		List<MarketNewsItem> candidates = List.of(
			news(1L, LocalTime.of(16, 0)), disclosure(101L), news(2L, LocalTime.of(18, 0)));

		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(candidates, 3);

		assertThat(selected).extracting(MarketNewsItem::getId).containsExactly(2L, 1L, 101L);
	}

	@Test
	@DisplayName("공시만으로 상한을 넘으면 뉴스 자리가 0이 된다")
	void leavesNoRoomForNewsWhenDisclosuresAlreadyExceedTheLimit() {
		List<MarketNewsItem> candidates = List.of(
			disclosure(101L), disclosure(102L), disclosure(103L), news(1L, LocalTime.of(20, 0)));

		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(candidates, 2);

		assertThat(selected).extracting(MarketNewsItem::getId).containsExactly(103L, 102L);
	}

	@Test
	@DisplayName("후보가 비어 있으면 빈 목록을 돌려준다")
	void returnsAnEmptyListWhenThereAreNoCandidates() {
		assertThat(NewsItemTruncator.truncateAndSort(List.of(), 5)).isEmpty();
	}

	@Test
	@DisplayName("입력 목록을 건드리지 않는다 — 호출부가 넘긴 목록의 순서가 그대로다")
	void doesNotMutateTheGivenCandidateList() {
		List<MarketNewsItem> candidates = new ArrayList<>(
			List.of(disclosure(101L), news(1L, LocalTime.of(16, 0)), news(2L, LocalTime.of(18, 0))));
		List<Long> before = candidates.stream().map(MarketNewsItem::getId).toList();

		NewsItemTruncator.truncateAndSort(candidates, 2);

		assertThat(candidates.stream().map(MarketNewsItem::getId).toList()).isEqualTo(before);
	}

	@Test
	@DisplayName("돌려주는 목록은 수정할 수 없다")
	void returnsAnImmutableList() {
		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(
			List.of(news(1L, LocalTime.of(16, 0))), 5);

		assertThat(selected).isUnmodifiable();
	}
}
