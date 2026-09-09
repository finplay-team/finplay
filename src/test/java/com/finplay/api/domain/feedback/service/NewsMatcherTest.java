package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsMatcherTest {

	private static final int SPEC_MATCH_BEFORE_MINUTES = 30;

	private static final int SPEC_MATCH_AFTER_MINUTES = 5;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	private static final Long INSTRUMENT_ID = 1L;

	private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);

	private static final LocalDate FRIDAY_BEFORE_MONDAY = LocalDate.of(2026, 7, 24);

	private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 28);

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private NewsMatcher matcher(int beforeMinutes, int afterMinutes, int maxSources) {
		return new NewsMatcher(
			marketNewsItemRepository,
			new FeedbackNewsProperties(
				"0 0/30 * * * *",
				"0 0/30 8-20 * * MON-FRI",
				beforeMinutes,
				afterMinutes,
				maxSources,
				50,
				30,
				30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, 30),
			new BusinessDayCalendar());
	}

	private NewsMatcher matcher() {
		return matcher(SPEC_MATCH_BEFORE_MINUTES, SPEC_MATCH_AFTER_MINUTES, SPEC_MAX_SOURCES_PER_CARD);
	}

	private static PriceMoveDetectionDto intraday(LocalTime windowEnd) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY,
			windowEnd.minusMinutes(5),
			windowEnd,
			new BigDecimal("0.020000"),
			new BigDecimal("3.0000"));
	}

	private static PriceMoveDetectionDto openingGap(LocalTime firstCandleTime) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			firstCandleTime,
			firstCandleTime,
			new BigDecimal("0.030000"),
			new BigDecimal("3.0000"));
	}

	private static MarketNewsItem news(LocalDateTime publishedAt) {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		return MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			publishedAt.toString(),
			"테스트경제",
			"https://news.example.com/" + publishedAt,
			publishedAt,
			publishedAt.plusMinutes(30));
	}

	private static MarketNewsItem disclosure(LocalDate receiptDate) {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		return MarketNewsItem.create(
			instrument,
			MarketNewsItemType.DISCLOSURE,
			"공시 " + receiptDate,
			"DART",
			"https://dart.fss.or.kr/" + receiptDate,
			receiptDate.atStartOfDay(),
			receiptDate.atStartOfDay().plusHours(9));
	}

	private static List<String> titles(List<MarketNewsItem> items) {
		return items.stream().map(MarketNewsItem::getTitle).toList();
	}

	@Test
	@DisplayName("장중은 [windowEnd - 30분, windowEnd + 5분]을 NEWS 종류로만 묻는다")
	void intradayQueriesNewsOnlyWithinTheConfiguredWindowAroundWindowEnd() {
		matcher().match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)));

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			INSTRUMENT_ID,
			MarketNewsItemType.NEWS,
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 30)),
			LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)));
	}

	@Test
	@DisplayName("장중은 공시 질의를 아예 부르지 않는다")
	void intradayNeverQueriesDisclosures() {
		matcher().match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)));

		verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(any(), any(), any());
	}

	@Test
	@DisplayName("근거창 폭은 설정값을 따른다 — 코드 상수가 아니다")
	void intradayWindowWidthComesFromConfiguration() {
		matcher(10, 1, SPEC_MAX_SOURCES_PER_CARD)
			.match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)));

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			INSTRUMENT_ID,
			MarketNewsItemType.NEWS,
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 50)),
			LocalDateTime.of(TUESDAY, LocalTime.of(10, 1)));
	}

	@Test
	@DisplayName("시가 갭 뉴스 구간은 [직전 영업일 15:30, D 09:00]이고 월요일이면 금요일이 하한이다")
	void openingGapQueriesPreMarketNewsWindowFromPreviousBusinessDay() {
		matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)));

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			INSTRUMENT_ID,
			MarketNewsItemType.NEWS,
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(15, 30)),
			LocalDateTime.of(MONDAY, LocalTime.of(9, 0)));
		verify(marketNewsItemRepository, never())
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				eq(INSTRUMENT_ID),
				eq(MarketNewsItemType.NEWS),
				eq(LocalDateTime.of(MONDAY.minusDays(1), LocalTime.of(15, 30))),
				any());
	}

	@Test
	@DisplayName("시가 갭 공시는 직전 영업일 하루를 반열림 구간으로 따로 묻는다")
	void openingGapQueriesDisclosuresByReceiptDateOfPreviousBusinessDay() {
		matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)));

		verify(marketNewsItemRepository).findDisclosuresReceivedOn(
			INSTRUMENT_ID,
			FRIDAY_BEFORE_MONDAY.atStartOfDay(),
			FRIDAY_BEFORE_MONDAY.plusDays(1).atStartOfDay());
	}

	@Test
	@DisplayName("첫 분봉이 09:03이어도 전장 상한은 벽시계 09:00 그대로다")
	void openingGapUpperBoundStaysAtWallClockNineEvenWhenFirstCandleIsLate() {
		matcher().match(INSTRUMENT_ID, TUESDAY, openingGap(LocalTime.of(9, 3)));

		verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			eq(INSTRUMENT_ID),
			eq(MarketNewsItemType.NEWS),
			any(),
			eq(LocalDateTime.of(TUESDAY, LocalTime.of(9, 0))));
	}

	@Test
	@DisplayName("시가 갭은 전장 뉴스가 상한을 채워도 D-1 공시를 먼저 남긴다")
	void openingGapKeepsTheDisclosureEvenWhenPreMarketNewsAlreadyFillsTheCap() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(List.of(
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(16, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(18, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(20, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(22, 0))),
				news(LocalDateTime.of(MONDAY, LocalTime.of(7, 0))),
				news(LocalDateTime.of(MONDAY, LocalTime.of(8, 50)))));
		when(marketNewsItemRepository.findDisclosuresReceivedOn(any(), any(), any()))
			.thenReturn(List.of(disclosure(FRIDAY_BEFORE_MONDAY)));

		List<MarketNewsItem> matched = matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)));

		assertThat(titles(matched)).containsExactly(
			LocalDateTime.of(MONDAY, LocalTime.of(8, 50)).toString(),
			LocalDateTime.of(MONDAY, LocalTime.of(7, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(22, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(20, 0)).toString(),
			"공시 " + FRIDAY_BEFORE_MONDAY);
	}

	@Test
	@DisplayName("시가 갭에 공시가 없으면 전장 뉴스 최신 5건만 남는다")
	void openingGapFallsBackToNewsOnlyWhenThereIsNoDisclosure() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(List.of(
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(16, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(18, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(20, 0))),
				news(LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(22, 0))),
				news(LocalDateTime.of(MONDAY, LocalTime.of(7, 0))),
				news(LocalDateTime.of(MONDAY, LocalTime.of(8, 50)))));
		when(marketNewsItemRepository.findDisclosuresReceivedOn(any(), any(), any())).thenReturn(List.of());

		List<MarketNewsItem> matched = matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)));

		assertThat(titles(matched)).containsExactly(
			LocalDateTime.of(MONDAY, LocalTime.of(8, 50)).toString(),
			LocalDateTime.of(MONDAY, LocalTime.of(7, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(22, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(20, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(18, 0)).toString());
	}

	@Test
	@DisplayName("이벤트 시각과 가까운 순으로 정렬해 max-sources-per-card만큼만 남긴다")
	void sortsByDistanceFromEventAndTruncatesToMaxSourcesPerCard() {
		LocalTime windowEnd = LocalTime.of(10, 0);
		List<MarketNewsItem> found = List.of(
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 30))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 40))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 53))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 56))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 58))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(10, 3))),
			news(LocalDateTime.of(TUESDAY, LocalTime.of(10, 5))));
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(found);

		List<MarketNewsItem> matched = matcher().match(INSTRUMENT_ID, TUESDAY, intraday(windowEnd));

		assertThat(titles(matched)).containsExactly(
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 58)).toString(),
			LocalDateTime.of(TUESDAY, LocalTime.of(10, 3)).toString(),
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 56)).toString(),
			LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)).toString(),
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 53)).toString());
	}

	@Test
	@DisplayName("이벤트로부터 거리가 같으면 늦게 발행된 쪽이 앞에 온다")
	void breaksDistanceTiesByLaterPublishedAt() {
		LocalTime windowEnd = LocalTime.of(10, 0);
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any()))
			.thenReturn(List.of(
				news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 58))),
				news(LocalDateTime.of(TUESDAY, LocalTime.of(10, 2)))));

		List<MarketNewsItem> matched = matcher().match(INSTRUMENT_ID, TUESDAY, intraday(windowEnd));

		assertThat(titles(matched)).containsExactly(
			LocalDateTime.of(TUESDAY, LocalTime.of(10, 2)).toString(),
			LocalDateTime.of(TUESDAY, LocalTime.of(9, 58)).toString());
	}

	@Test
	@DisplayName("상한 이하이면 자르지 않고 같은 정렬로 전건을 돌려준다")
	void keepsEveryCandidateWhenCountIsWithinTheLimit() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any()))
			.thenReturn(List.of(
				news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 40))),
				news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 59)))));

		assertThat(matcher().match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)))).hasSize(2);
	}

	@Test
	@DisplayName("근거창 안에 기사가 없으면 예외 없이 빈 목록이다")
	void returnsEmptyListWhenNoArticleIsInTheWindow() {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			any(), any(), any(), any())).thenReturn(List.of());
		when(marketNewsItemRepository.findDisclosuresReceivedOn(any(), any(), any())).thenReturn(List.of());

		assertThat(matcher().match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)))).isEmpty();
		assertThat(matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)))).isEmpty();
	}
}
