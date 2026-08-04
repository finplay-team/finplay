// 탐지된 변동 구간에 붙일 근거 기사를 고르는 매처 — 근거창 판정과 상한 절단만 하고 저장·수집은 하지 않는다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근거창은 spec §C-2, 공시 판정은 §C-3, 상한과 절단은 §뉴스 매칭 범위, 값은 §C-7이 정본이다.
 *
 * <p><b>이미 저장된 기사를 읽기만 한다.</b> 수집은 기사가 나오는 당일에 상시로 도는 별도 경로이며
 * (FEED-001·004) 이 매처는 수집기·수집 크론·질의어·제목 필터를 알지 못한다.
 *
 * <p><b>근거가 없으면 빈 목록이다 — 오류가 아니다.</b> 그 결과로 카드를 만들지 않는 판단은 카드 확정 경로
 * ({@code PriceMoveCardService})의 몫이다 (FEED-003). 근거 없는 문장은 LLM이 지어낸 것이 되므로 여기서
 * 근거창을 넓혀 억지로 채우지 않는다.
 *
 * <p><b>장중 카드와 시가 갭이 서로 다른 범위를 쓴다</b>(§C-2). 장중은 {@code windowEnd} 앞뒤의 분 단위
 * 근거창이고, 시가 갭은 전장 {@code [D-1 15:30, D 09:00]}이다. 전장의 경계 {@code 15:30}·{@code 09:00}은
 * <b>벽시계</b>다 — 기사 필터일 뿐 분봉을 찾지 않으므로 "마지막 분봉"으로 바꾸면 안 된다 (§C-2-1).
 * 그 두 값은 {@link MarketSessionTimes}가 단일 출처로 갖는다 (§C-6).
 */
@Component
@RequiredArgsConstructor
public class NewsMatcher {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final FeedbackNewsProperties properties;

	private final BusinessDayCalendar businessDayCalendar;

	/**
	 * 변동 구간 1건에 붙일 근거 기사를 고른다.
	 *
	 * @param originTradeDate 원본 거래일 {@code D}. {@code detection}의 시각은 이 거래일 시간축이다
	 * @return 발행시각이 <b>이벤트에 가까운 순</b>으로 정렬된 근거 목록. 상한을 넘으면 잘라낸다
	 *     (§뉴스 매칭 범위). 근거창 안에 기사가 없으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<MarketNewsItem> match(
		Long instrumentId, LocalDate originTradeDate, PriceMoveDetectionDto detection) {
		List<MarketNewsItem> candidates = switch (detection.eventType()) {
			case INTRADAY -> matchIntraday(instrumentId, originTradeDate, detection.windowEnd());
			case OPENING_GAP -> matchOpeningGap(instrumentId, originTradeDate);
		};
		return sortAndTruncate(candidates, LocalDateTime.of(originTradeDate, detection.windowEnd()));
	}

	/**
	 * 장중 카드 근거 — {@code [windowEnd - match-before-minutes, windowEnd + match-after-minutes]} (§C-2).
	 *
	 * <p><b>공시는 매칭하지 않는다</b> (FEED-003). OpenDART가 접수일자만 줘서 {@code published_at}이 그 날짜
	 * {@code 00:00:00}이라 분 단위 매칭이 성립하지 않는다 — 시각으로 걸면 장중 근거창에는 절대 들어오지
	 * 않으므로 "안 걸러도 어차피 안 들어온다"가 맞지만, 규칙을 시각의 우연에 맡기지 않고 종류로 못박는다.
	 */
	private List<MarketNewsItem> matchIntraday(
		Long instrumentId, LocalDate originTradeDate, LocalTime windowEnd) {
		LocalDateTime eventAt = LocalDateTime.of(originTradeDate, windowEnd);
		return marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			instrumentId,
			MarketNewsItemType.NEWS,
			eventAt.minusMinutes(properties.matchBeforeMinutes()),
			eventAt.plusMinutes(properties.matchAfterMinutes()));
	}

	/**
	 * 시가 갭 근거 — 전장 {@code [D-1 15:30, D 09:00]}의 뉴스와 {@code rcept_dt = D-1}인 공시다 (§C-2·§C-3).
	 *
	 * <p><b>뉴스와 공시를 같은 질의로 가져오지 않는다.</b> 공시를 같은 datetime 구간에 태우면 정확히 반대로
	 * 걸린다 — {@code D-1} 접수분은 {@code D-1 00:00:00}이라 전장 시작보다 이르러 <b>빠지고</b>, {@code D}
	 * 접수분은 {@code D 00:00:00}이라 전장 안에 <b>들어오는데</b> 거기엔 {@code D} 장중 접수분이 섞여 있어
	 * 개장 전에 그날 장중 공시가 새어 나간다. 그래서 뉴스는 시각 구간, 공시는 날짜로 따로 묻는다.
	 *
	 * <p>{@code D-1}은 {@link BusinessDayCalendar#previousBusinessDay}로 구한다 — 시장 단위 직전 거래일을
	 * 얻는 유일한 경로이며(§C-6), 종목별 직전 종가 조회로 대체할 수 없다.
	 */
	private List<MarketNewsItem> matchOpeningGap(Long instrumentId, LocalDate originTradeDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		List<MarketNewsItem> candidates = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId,
				MarketNewsItemType.NEWS,
				LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
				LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME)));
		candidates.addAll(marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentId,
			previousTradingDate.atStartOfDay(),
			previousTradingDate.plusDays(1).atStartOfDay()));
		return candidates;
	}

	// 상한을 넘으면 발행시각이 이벤트에 가까운 순으로 자른다 (§뉴스 매칭 범위). 자르지 않는 경우에도 같은
	// 순서로 돌려준다 — 순서가 그때그때 다르면 프롬프트에 실리는 기사 순서와 화면 순서가 함께 흔들린다.
	// 동률은 늦게 발행된 쪽, 그래도 같으면 url 순으로 갈라 결과를 결정적으로 만든다(url은 종목 안에서 유일하다).
	private List<MarketNewsItem> sortAndTruncate(List<MarketNewsItem> candidates, LocalDateTime eventAt) {
		List<MarketNewsItem> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator
			.comparing((MarketNewsItem item) -> Duration.between(eventAt, item.getPublishedAt()).abs())
			.thenComparing(MarketNewsItem::getPublishedAt, Comparator.reverseOrder())
			.thenComparing(MarketNewsItem::getUrl));
		int limit = properties.maxSourcesPerCard();
		return sorted.size() <= limit ? List.copyOf(sorted) : List.copyOf(sorted.subList(0, limit));
	}
}
