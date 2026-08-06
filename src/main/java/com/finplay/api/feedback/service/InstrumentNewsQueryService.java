// 종목의 뉴스·공시 목록과 그 시점의 요약을 노출 게이트에 맞춰 조회하는 읽기 전용 서비스.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.store.FeedbackQueryCache;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약은 {@code docs/api-contracts.md}의 "종목 뉴스 목록·요약 조회" 행, 상태값과 판정 순서는 spec §C-4,
 * 구간과 {@code summaryScope} 대응은 §C-2, 공시 날짜 판정은 §C-3, 노출 게이트는 §C-5가 정본이다.
 *
 * <p><b>쓰지 않는다.</b> 요약은 전 회원이 공유하는 배치 산출물이라 조회가 만들지 않는다 — GET이 LLM을
 * 호출하지도 DB에 쓰지도 않는다({@code docs/conventions.md}, FEED-008). 배치로 옮긴 이유는 비용이 아니라
 * <b>스포일러 차단과 첫 사용자의 대기</b>다.
 *
 * <p><b>어느 상태값이든 200이다</b>(FEED-008). 없는 종목만 404이며 그 판정은 {@code InstrumentService}가 한다.
 */
@Service
@RequiredArgsConstructor
public class InstrumentNewsQueryService {

	private final InstrumentService instrumentService;

	private final StockReplayService stockReplayService;

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	private final BusinessDayCalendar businessDayCalendar;

	// 요약 텍스트만 이 캐시를 거친다. items 수집(collectVisibleItems·코인 24시간 창)은 §C-5 노출 게이트의
	// 구현이라 캐시하지 않는다 — 캐시하면 게이트가 늦게 열린다(ADR-0015 §1).
	private final FeedbackQueryCache feedbackQueryCache;

	private final FeedbackNewsProperties properties;

	private final Clock clock;

	/**
	 * 종목의 기사 목록과 그 시각의 요약을 조회한다.
	 *
	 * <p><b>판정 순서는 §C-4의 표 그대로다.</b> 순서를 바꾸면 두 조건이 동시에 성립하는 구간(재생세션 미준비
	 * + 개장 전)에서 값이 갈린다 — 1번이 {@code originTradeDate}까지 {@code null}인데 2번은 채우기 때문이다.
	 *
	 * <pre>
	 * 1. 재생세션 미준비 → NOT_YET, originTradeDate=null
	 * 2. 09:00 이전     → NOT_YET, originTradeDate 채움
	 * 3. 기사 0건       → EMPTY,   items=[]
	 * 4. 요약 행 없음   → EMPTY,   items 채움
	 * 5. 행은 있고 summary가 null → UNAVAILABLE, items 채움
	 * 6. 그 외          → READY
	 * </pre>
	 *
	 * @param instrumentId 없는 종목이면 {@code InstrumentService}가 404({@code NOT_FOUND})로 거절한다
	 */
	@Transactional(readOnly = true)
	public InstrumentNewsResponse getInstrumentNews(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() == Market.CRYPTO) {
			return getCryptoNews(instrumentId);
		}

		// 1번 — 원본 거래일 자체가 확정되지 않은 상태라 날짜를 지어낼 수 없다.
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return InstrumentNewsResponse.notYet(null);
		}

		// 2번 — Part D와 같은 전장 기사군을 다루므로 하한을 맞춘다. 없으면 08:41에 이 API로 조회해
		// 브리핑이 09:00까지 감추는 기사를 20분 먼저 볼 수 있다 (FEED-008).
		LocalDate originTradeDate = session.sourceTradingDate();
		LocalTime now = LocalTime.now(clock);
		if (now.isBefore(MarketSessionTimes.MARKET_OPEN_TIME)) {
			return InstrumentNewsResponse.notYet(originTradeDate);
		}

		NewsSummaryScope scope = resolveScope(now);
		List<NewsItem> items = NewsItemTruncator
			.truncateAndSort(collectVisibleItems(instrumentId, originTradeDate, scope, now),
				properties.maxItemsPerNewsList())
			.stream()
			.map(NewsItem::from)
			.toList();

		// 3번 — 기사가 0건이면 요약 행이 있든 없든 EMPTY다. 행 조회보다 앞이라 순서를 바꾸면
		// "기사도 없고 서술도 없는" 날이 UNAVAILABLE로 보인다.
		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.EMPTY, null, List.of());
		}

		// 4·5·6번 — 요약 텍스트만 캐시를 거친다(ADR-0015 §1). items는 위에서 이미 매 요청 DB로 모았다.
		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadStockSummaryText(
			instrumentId, originTradeDate, scope,
			() -> {
				Optional<InstrumentNewsSummary> summary = instrumentNewsSummaryRepository
					.findByInstrumentIdAndOriginTradeDateAndScope(instrumentId, originTradeDate, scope);
				summaryRowFound.set(summary.isPresent());
				return summary.map(InstrumentNewsSummary::getSummary);
			});
		// 캐시는 서술이 있는 값(READY)만 담으므로 적중은 곧 6번이다.
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.READY, text.get(), items);
		}

		// 미적중이면 위 로더가 반드시 실행됐다(캐시는 값이 없으면 항상 로더를 부른다). 그 DB 결과로 4·5번을
		// 지금 로직 그대로 가른다 — 저장된 행만으로는 둘이 구분되지 않으므로(둘 다 summary가 NULL) 행 존재
		// 여부와 함께 갈라야 한다. 판정 순서는 §C-4 그대로다.
		return InstrumentNewsResponse.of(
			originTradeDate,
			scope,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	/**
	 * 코인 종목 조회 — 최근 24시간 기사와 {@code generated_at} 최신 1행이다 (FEED-008).
	 *
	 * <p><b>주식의 게이트·판정 순서를 타지 않는다.</b> 코인은 재생세션과 무관하고 '개장 전'이라는 시점이 없어
	 * §C-4의 1·2번이 성립하지 않는다 — {@code NOT_YET}이 되지 않는다. 3~6번은 그대로 쓴다.
	 *
	 * <p><b>{@code summaryScope}는 언제나 {@code ROLLING_24H}이고 {@code originTradeDate}는 {@code null}이다</b>
	 * (§C-2·§C-9). 저장된 행의 {@code origin_trade_date}에는 값이 있지만 그것은 유니크 축을 성립시키려고 채운
	 * <b>배치 실행 날짜</b>이지 거래일이 아니다.
	 *
	 * <p><b>{@code items}의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 최대 65분 어긋난다 —
	 * 허용된 동작이다</b>(FEED-008). 맞추려고 조회 시 생성으로 되돌아가지 않는다.
	 *
	 * <p>코인은 공시가 없어 뉴스만 모은다(§C-3). 노출 게이트도 없다 — 실시간이라 스포일러가 성립하지 않는다(§C-5).
	 */
	private InstrumentNewsResponse getCryptoNews(Long instrumentId) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<NewsItem> items = NewsItemTruncator
			.truncateAndSort(
				marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
					instrumentId,
					MarketNewsItemType.NEWS,
					now.minus(MarketSessionTimes.ROLLING_WINDOW),
					now),
				properties.maxItemsPerNewsList())
			.stream()
			.map(NewsItem::from)
			.toList();
		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.EMPTY, null, List.of());
		}

		// 주식과 같은 형태다 — 요약 텍스트만 캐시를 거치고 items의 24시간 창은 그대로 매 요청 DB로 간다.
		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadCryptoSummaryText(instrumentId, () -> {
			Optional<InstrumentNewsSummary> summary = instrumentNewsSummaryRepository
				.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
					instrumentId, NewsSummaryScope.ROLLING_24H);
			summaryRowFound.set(summary.isPresent());
			return summary.map(InstrumentNewsSummary::getSummary);
		});
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.READY, text.get(), items);
		}

		return InstrumentNewsResponse.of(
			null,
			NewsSummaryScope.ROLLING_24H,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	/**
	 * 조회 시각이 보는 요약 범위 (§C-2).
	 *
	 * <p><b>{@code FULL}을 09:00에 노출하면 안 된다</b>(FEED-008). 하루 전체를 요약한 문장은 장중 기사를
	 * 언급하므로 {@code items}에 게이트를 걸어도 <b>요약 한 문장이 그날 오후를 통째로 알려준다.</b>
	 */
	private static NewsSummaryScope resolveScope(LocalTime now) {
		return now.isBefore(MarketSessionTimes.MARKET_CLOSE_TIME)
			? NewsSummaryScope.PRE_MARKET
			: NewsSummaryScope.FULL;
	}

	/**
	 * 그 시각에 노출 가능한 기사·공시를 모은다 (§C-2의 구간, §C-3의 공시 날짜, §C-5의 게이트).
	 *
	 * <p><b>하한은 요약과 같고 상한만 재생 시각까지 넓다.</b> 09:00~15:30에는 {@code items}가 요약보다 넓다 —
	 * 장중 기사가 재생 시각을 따라 하나씩 풀리기 때문이다. 요약이 {@code items}보다 <b>앞서지만 않으면</b>
	 * 되며, 반대로 {@code items}를 09:00에서 자르면 "재생 시각을 지난 기사만 노출"이 깨진다.
	 *
	 * <p><b>구간 질의가 곧 게이트다.</b> §C-5는 {@code (서비스 날짜 + clamp(published_at)) <= now()}인데,
	 * 재생이 1배속이라 원본 거래일 시각과 서비스 날짜의 벽시계 시각이 1:1로 대응하므로 상한을 현재 시각으로
	 * 두는 것이 그 판정과 같다({@code PriceMoveQueryService}가 {@code reveal_time}에 쓰는 것과 같은 성질).
	 * 전장 기사는 클램프 결과가 09:00이고 이 메서드에는 09:00 이후에만 들어오므로 전부 노출 대상이다.
	 *
	 * <p><b>뉴스와 공시를 같은 질의로 가져오지 않는다</b>(§C-3). 공시는 {@code published_at}이 접수일
	 * {@code 00:00:00}이라 datetime 구간에 태우면 {@code D-1} 접수분이 구간 시작보다 일러 빠지고, {@code D}
	 * 접수분은 {@code PRE_MARKET} 구간에 들어와 <b>개장 직후에 그날 장중 접수 공시가 새어 나간다.</b>
	 * {@code D} 접수분이 {@code FULL}에만 있는 것도 그래서다 — {@code FULL}은 15:30 이후에만 노출된다.
	 *
	 * <p>범위는 {@code InstrumentNewsSummaryService}의 요약 생성 구간과 하한·공시 규칙이 같고 뉴스 상한만
	 * 다르다. 상한이 다른 것이 이 API의 요지라 한 메서드로 합치지 않는다.
	 */
	private List<MarketNewsItem> collectVisibleItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope, LocalTime now) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		// FULL이면 15:30에서 멈춘다 — 그 뒤 시각은 원본 거래일의 장중이 아니라서 기사가 있을 수 없고,
		// 상한을 열어 두면 다음 거래일 새벽 기사가 그날 목록에 섞인다.
		LocalTime newsUpperBound = now.isBefore(MarketSessionTimes.MARKET_CLOSE_TIME)
			? now
			: MarketSessionTimes.MARKET_CLOSE_TIME;

		List<MarketNewsItem> items = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId,
				MarketNewsItemType.NEWS,
				LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
				LocalDateTime.of(originTradeDate, newsUpperBound)));
		items.addAll(disclosuresOn(instrumentId, previousTradingDate));
		if (scope == NewsSummaryScope.FULL) {
			items.addAll(disclosuresOn(instrumentId, originTradeDate));
		}
		return items;
	}

	private List<MarketNewsItem> disclosuresOn(Long instrumentId, LocalDate receivedDate) {
		return marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentId, receivedDate.atStartOfDay(), receivedDate.plusDays(1).atStartOfDay());
	}
}
