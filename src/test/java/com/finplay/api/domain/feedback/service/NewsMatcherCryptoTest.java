package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsMatcherCryptoTest {

	private static final int SPEC_MATCH_BEFORE_MINUTES = 35;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	private static final Long INSTRUMENT_ID = 1L;

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 7, 28, 10, 0);

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private NewsMatcher matcher(int cryptoMatchBeforeMinutes, int maxSources) {
		return new NewsMatcher(
			marketNewsItemRepository,
			new FeedbackNewsProperties(
				"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, maxSources, 50, 30, 30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, cryptoMatchBeforeMinutes, 30),
			new BusinessDayCalendar());
	}

	private NewsMatcher matcher() {
		return matcher(SPEC_MATCH_BEFORE_MINUTES, SPEC_MAX_SOURCES_PER_CARD);
	}

	private static MarketNewsItem news(LocalDateTime publishedAt) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		return MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			publishedAt.toString(),
			"테스트경제",
			"https://news.example.com/" + publishedAt,
			publishedAt,
			publishedAt.plusMinutes(30));
	}

	private static List<String> titles(List<MarketNewsItem> items) {
		return items.stream().map(MarketNewsItem::getTitle).toList();
	}


	@Test
	@DisplayName("코인 근거창은 [occurredAt - match-before-minutes, occurredAt]을 NEWS 종류로만 묻는다")
	void matchCryptoQueriesNewsOnlyWithinTheConfiguredWindowEndingAtOccurredAt() {
		matcher().matchCrypto(INSTRUMENT_ID, OCCURRED_AT);

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			INSTRUMENT_ID,
			MarketNewsItemType.NEWS,
			OCCURRED_AT.minusMinutes(SPEC_MATCH_BEFORE_MINUTES),
			OCCURRED_AT);
	}

	@Test
	@DisplayName("근거창 상한은 항상 occurredAt이다 — 이후 방향은 열지 않는다")
	void matchCryptoUpperBoundIsAlwaysOccurredAt() {
		matcher().matchCrypto(INSTRUMENT_ID, OCCURRED_AT);

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), org.mockito.ArgumentMatchers.eq(OCCURRED_AT));
	}

	@Test
	@DisplayName("근거창 이전 폭은 feedback.crypto.match-before-minutes를 따른다 — 코드 상수가 아니다")
	void matchCryptoWindowWidthComesFromConfiguration() {
		matcher(10, SPEC_MAX_SOURCES_PER_CARD).matchCrypto(INSTRUMENT_ID, OCCURRED_AT);

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			INSTRUMENT_ID, MarketNewsItemType.NEWS, OCCURRED_AT.minusMinutes(10), OCCURRED_AT);
	}


	@Test
	@DisplayName("코인 근거 매칭은 공시 질의를 아예 부르지 않는다")
	void matchCryptoNeverQueriesDisclosures() {
		matcher().matchCrypto(INSTRUMENT_ID, OCCURRED_AT);

		verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(any(), any(), any());
	}


	@Test
	@DisplayName("근거가 max-sources-per-card를 넘으면 occurredAt에 가까운 순으로 자른다")
	void matchCryptoSortsByDistanceFromOccurredAtAndTruncatesToMaxSourcesPerCard() {
		List<MarketNewsItem> found = List.of(
			news(OCCURRED_AT.minusMinutes(30)),
			news(OCCURRED_AT.minusMinutes(20)),
			news(OCCURRED_AT.minusMinutes(7)),
			news(OCCURRED_AT.minusMinutes(4)),
			news(OCCURRED_AT.minusMinutes(2)),
			news(OCCURRED_AT.minusMinutes(3)),
			news(OCCURRED_AT.minusMinutes(5)));
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(found);

		List<MarketNewsItem> matched = matcher(SPEC_MATCH_BEFORE_MINUTES, 5).matchCrypto(INSTRUMENT_ID, OCCURRED_AT);

		assertThat(titles(matched)).containsExactly(
			OCCURRED_AT.minusMinutes(2).toString(),
			OCCURRED_AT.minusMinutes(3).toString(),
			OCCURRED_AT.minusMinutes(4).toString(),
			OCCURRED_AT.minusMinutes(5).toString(),
			OCCURRED_AT.minusMinutes(7).toString());
	}

	@Test
	@DisplayName("상한 이하이면 자르지 않고 전건을 돌려준다")
	void matchCryptoKeepsEveryCandidateWhenCountIsWithinTheLimit() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any()))
			.thenReturn(List.of(news(OCCURRED_AT.minusMinutes(10)), news(OCCURRED_AT.minusMinutes(1))));

		assertThat(matcher().matchCrypto(INSTRUMENT_ID, OCCURRED_AT)).hasSize(2);
	}

	@Test
	@DisplayName("근거창 안에 기사가 없으면 예외 없이 빈 목록이다")
	void matchCryptoReturnsEmptyListWhenNoArticleIsInTheWindow() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(List.of());

		assertThat(matcher().matchCrypto(INSTRUMENT_ID, OCCURRED_AT)).isEmpty();
	}
}
