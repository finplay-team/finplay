// 목록·프롬프트 절단 규칙(공시 우선 + 발행시각·id 내림차순 정렬)을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
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

// 기대 규칙의 정본은 spec.md §뉴스 매칭 범위다 (2026-08-04 개정) — 상한을 넘으면 공시를 먼저 채우고 남은
// 자리를 뉴스 최신순으로 채운다. 정렬은 발행시각 내림차순 + id 내림차순이며 절단과 별개다.
//
// 픽스처를 전부 상한 위로 잡는다. spec이 명시한 대로 상한 아래 픽스처는 절단 자체가 일어나지 않아
// "공시 우선"의 유무를 구분하지 못하고, 규칙을 통째로 되돌려도 초록이 된다.
class NewsItemTruncatorTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	// 공시는 rcept_dt만 있어 published_at이 그날 00:00:00이다 (§C-3·§C-8). 이 값이 이 규칙의 전제다 —
	// 같은 목록의 뉴스는 전부 D-1 15:30 이후라 공시는 내림차순 목록에서 예외 없이 최하위다.
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

	// 뉴스 10건(16:00부터 10분 간격) + 공시 2건. 상한 5는 뉴스만으로도 이미 넘는다.
	//
	// 간격을 시간이 아니라 분으로 잡는다 — LocalTime은 자정을 넘으면 되감기므로 1시간 간격이면 9·10번째가
	// 00:00·01:00이 되어 오히려 가장 이른 기사가 되고, 00:00은 공시 시각과 동률이 되어 픽스처가 흐려진다.
	private static List<MarketNewsItem> tenNewsAndTwoDisclosures() {
		List<MarketNewsItem> candidates = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			candidates.add(news(index + 1L, LocalTime.of(16, 0).plusMinutes(index * 10L)));
		}
		candidates.add(disclosure(101L));
		candidates.add(disclosure(102L));
		// 입력 순서에 기대지 않는다 — 실제 호출부는 뉴스 질의 결과에 공시를 이어 붙이므로 순서가 섞여 있다.
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
		// 남은 3자리는 가장 늦게 발행된 뉴스 3건(23:00·22:00·21:00 = id 8·9·10)이다.
		assertThat(selected)
			.filteredOn(each -> each.getType() == MarketNewsItemType.NEWS)
			.extracting(MarketNewsItem::getId)
			.containsExactly(10L, 9L, 8L);
	}

	// "공시 우선"은 누구를 남기느냐의 규칙이지 순서의 규칙이 아니다. 절단 뒤 순서는 계약대로 발행시각
	// 내림차순이라 공시가 목록 아래쪽에 온다 — 여기를 위로 올리면 Part C·D 계약이 깨진다.
	@Test
	@DisplayName("절단 뒤 순서는 발행시각 내림차순 그대로다 — 공시가 목록 위로 올라오지 않는다")
	void ordersSurvivorsByPublishedAtDescendingSoDisclosuresStayAtTheBottom() {
		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(tenNewsAndTwoDisclosures(), 5);

		assertThat(selected).extracting(MarketNewsItem::getId).containsExactly(10L, 9L, 8L, 102L, 101L);
		assertThat(selected).extracting(MarketNewsItem::getPublishedAt).isSortedAccordingTo(
			java.util.Comparator.reverseOrder());
	}

	// 공시는 발행시각이 전부 00:00:00이라 동률이 흔하다. 2차 키가 없으면 InnoDB가 흔히 PK 순서로 돌려주어
	// 우연히 맞는 날이 있고, 그래서 이 축은 단정으로만 고정된다.
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

	// 공시만으로 상한을 넘는 날도 있다 — 그때는 공시끼리 최신순(여기서는 id 내림차순)으로 잘리고 뉴스 자리가
	// 0이 된다. 뉴스가 있다고 해서 한 자리를 억지로 비워 두지 않는다.
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

	// 이 목록은 그대로 응답과 프롬프트로 나간다 — 호출부가 뒤에서 정렬을 덧붙이는 것을 막으려면 불변이어야 한다.
	@Test
	@DisplayName("돌려주는 목록은 수정할 수 없다")
	void returnsAnImmutableList() {
		List<MarketNewsItem> selected = NewsItemTruncator.truncateAndSort(
			List.of(news(1L, LocalTime.of(16, 0))), 5);

		assertThat(selected).isUnmodifiable();
	}
}
