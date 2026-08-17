// NewsMatcher가 spec 012 §C-2·§C-3의 근거창을 어떤 질의 인자로 묻고 §뉴스 매칭 범위대로 자르는지 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 여기서는 "어떤 구간을 어떤 종류로 묻는가"와 "받은 목록을 어떻게 정렬·절단하는가"만 본다. 구간 경계가
// 실제로 양끝 포함인지, §C-3의 날짜 판정이 datetime 구간과 반대로 걸리는지는 실 DB가 필요하므로
// NewsMatcherMatchingWindowTest가 맡는다 — mock으로 끝내지 않는다(ADR-0003).
//
// 기대값의 정본은 spec.md다 — 근거창은 §C-2, 공시 판정은 §C-3, 절단은 §뉴스 매칭 범위, 값은 §C-7이다.
class NewsMatcherTest {

	// §C-7 feedback.news 기본값
	private static final int SPEC_MATCH_BEFORE_MINUTES = 30;

	private static final int SPEC_MATCH_AFTER_MINUTES = 5;

	private static final int SPEC_MAX_SOURCES_PER_CARD = 5;

	private static final Long INSTRUMENT_ID = 1L;

	// 2026-07-27(월) — 직전 영업일이 주말을 건너뛴 2026-07-24(금)이다.
	private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);

	private static final LocalDate FRIDAY_BEFORE_MONDAY = LocalDate.of(2026, 7, 24);

	// 2026-07-28(화) — 직전 영업일이 바로 전날(월)인 단순 케이스
	private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 28);

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private NewsMatcher matcher(int beforeMinutes, int afterMinutes, int maxSources) {
		return new NewsMatcher(
			marketNewsItemRepository,
			// 뒤 세 값은 §C-7의 목록 상한 3종이다 — 근거 매칭과 무관해 이 테스트는 쓰지 않는다.
			new FeedbackNewsProperties(
				"0 0/30 * * * *",
				"0 0/30 8-20 * * MON-FRI",
				beforeMinutes,
				afterMinutes,
				maxSources,
				50,
				30,
				30),
			// 주식 근거 매칭과 무관해 §C-7 기본값을 그대로 둔다.
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

	// 공시의 published_at은 접수일 00:00:00이다 (§C-3) — 이 테스트의 핵심이 그 시각이라 news()와 나눠 둔다.
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

	// --- 장중 근거창 (§C-2) ---

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

	// FEED-003 — 장중 카드에 공시를 매칭하지 않는다. 공시 질의를 아예 부르지 않는 것이 그 형태다.
	@Test
	@DisplayName("장중은 공시 질의를 아예 부르지 않는다")
	void intradayNeverQueriesDisclosures() {
		matcher().match(INSTRUMENT_ID, TUESDAY, intraday(LocalTime.of(10, 0)));

		verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(any(), any(), any());
	}

	// 근거창 폭이 코드 상수가 아니라 §C-7 설정에서 온다는 것을 단정한다 — 상수로 박히면 §튜닝이 성립하지 않는다.
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

	// --- 시가 갭 근거창 (§C-2 전장 · §C-3 공시) ---

	// 전장 하한의 D-1은 BusinessDayCalendar.previousBusinessDay다. D.minusDays(1)로 짜면 월요일에
	// 일요일 15:30이 하한이 되어 금요일 저녁~토요일 기사가 통째로 빠진다.
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

	// §C-3 — 공시는 접수일자 하루를 [00:00, 다음 날 00:00) 반열림으로 묻는다. 하루의 끝을 23:59:59로 적으면
	// DATETIME(6) 정밀도에서 조용히 틀린다.
	@Test
	@DisplayName("시가 갭 공시는 직전 영업일 하루를 반열림 구간으로 따로 묻는다")
	void openingGapQueriesDisclosuresByReceiptDateOfPreviousBusinessDay() {
		matcher().match(INSTRUMENT_ID, MONDAY, openingGap(LocalTime.of(9, 0)));

		verify(marketNewsItemRepository).findDisclosuresReceivedOn(
			INSTRUMENT_ID,
			FRIDAY_BEFORE_MONDAY.atStartOfDay(),
			FRIDAY_BEFORE_MONDAY.plusDays(1).atStartOfDay());
	}

	// 첫 분봉이 개장 정각이 아니어도 전장 상한은 벽시계 09:00이다 (§C-2-1) — "첫 분봉 시각"으로 바꾸면 안 된다.
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

	// --- 시가 갭 절단: 공시 우선 (이슈 #409) ---

	// 회귀: 거리순으로만 자르면 공시는 전멸한다. 공시의 published_at은 접수일 00:00:00이고 시가 갭의 이벤트
	// 시각은 D 09:00이라 거리가 약 33시간인데, 같은 후보의 뉴스는 전부 [D-1 15:30, D 09:00] 안이라 최대
	// 17.5시간이다 — 공시는 확률이 아니라 순서상 구조로 항상 최하위다.
	//
	// 픽스처의 뉴스 수를 반드시 max-sources-per-card 위로 잡아야 한다. 상한 아래면 절단 자체가 일어나지 않아
	// 규칙의 유무를 구분하지 못한다(§뉴스 매칭 범위가 경고한 그대로다).
	@Test
	@DisplayName("시가 갭은 전장 뉴스가 상한을 채워도 D-1 공시를 먼저 남긴다")
	void openingGapKeepsTheDisclosureEvenWhenPreMarketNewsAlreadyFillsTheCap() {
		// 상한이 5인데 뉴스만 6건이다 — 거리순이면 공시가 들어갈 자리가 없다.
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

		// 공시 1건 + 남은 4자리를 최신 뉴스가 채운다. 시가 갭에서는 "이벤트에 가까운 순"과 "최신순"이 같은
		// 순서라(후보가 전부 09:00 이전) 뉴스가 실리는 차례는 거리순 절단일 때와 동일하다.
		assertThat(titles(matched)).containsExactly(
			LocalDateTime.of(MONDAY, LocalTime.of(8, 50)).toString(),
			LocalDateTime.of(MONDAY, LocalTime.of(7, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(22, 0)).toString(),
			LocalDateTime.of(FRIDAY_BEFORE_MONDAY, LocalTime.of(20, 0)).toString(),
			"공시 " + FRIDAY_BEFORE_MONDAY);
	}

	// 공시가 없는 날은 지금까지와 똑같이 최신 5건이다 — "공시 우선"이 뉴스 선택 자체를 바꾸지 않는다.
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

	// --- 정렬·절단 (§뉴스 매칭 범위) ---

	// 상한을 넘으면 "발행시각이 이벤트에 가까운 순"으로 자른다. 단순 발행시각 정렬로 자르면 결과가 갈린다 —
	// 오름차순이면 남는 집합 자체가 다르고, 내림차순이면 집합은 같아도 순서가 다르다.
	@Test
	@DisplayName("이벤트 시각과 가까운 순으로 정렬해 max-sources-per-card만큼만 남긴다")
	void sortsByDistanceFromEventAndTruncatesToMaxSourcesPerCard() {
		LocalTime windowEnd = LocalTime.of(10, 0);
		List<MarketNewsItem> found = List.of(
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 30))), // 30분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 40))), // 20분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 53))), // 7분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 56))), // 4분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(9, 58))), // 2분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(10, 3))), // 3분
			news(LocalDateTime.of(TUESDAY, LocalTime.of(10, 5)))); // 5분
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

	// 순서가 실행마다 흔들리면 프롬프트에 실리는 기사 순서와 화면 순서가 함께 흔들린다.
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

	// 탐지 ⑥의 매처 쪽 절반 — 근거가 없으면 빈 목록이고 오류가 아니다. 카드를 만들지 않는다는 단정은 4번이 한다.
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
