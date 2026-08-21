// 종목 뉴스 조회가 필요로 하는 DB 읽기를 각각 한 트랜잭션 안에서 끝내고 DTO로만 내보내는 읽기 전용 컴포넌트.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import com.finplay.api.domain.market.service.InstrumentService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code InstrumentNewsQueryService}에서 <b>DB에 닿는 부분만</b> 떼어 낸 것이다. 각 메서드가 자기 트랜잭션을
 * 열고 닫으므로 호출부는 그 사이에 커넥션을 쥐지 않는다 — {@code PostSellFeedbackReader}와 같은 형태이며
 * 이유도 같다(그쪽은 LLM 호출, 여기는 조회 캐시의 락 대기가 트랜잭션 안에 들어오면 안 된다).
 *
 * <p><b>왜 나눴는가.</b> 조회 메서드 하나를 통째로 {@code @Transactional(readOnly = true)}로 두면, 종목·재생
 * 세션·{@code items} 질의로 이미 잡힌 JDBC 커넥션을 쥔 채 {@code FeedbackQueryCache}의 폴링 대기(최대
 * {@code feedback.query-cache.wait-millis})가 돈다. 만료 경계에 요청이 몰리는 순간 — <b>이 방어가 겨냥하는 바로
 * 그 순간</b> — 대기 스레드가 커넥션 풀을 채워 뒤따르는 요청이 커넥션 획득에서 막힌다. 막으려던 것보다 나쁜
 * 실패 형태라 <b>DB 작업과 그 결과 매핑은 트랜잭션 안에, 캐시 대기는 밖에</b> 두도록 갈랐다(PR #257 남은 위험 1).
 *
 * <p><b>엔티티를 내보내지 않는다.</b> 매핑({@code NewsItem::from})까지 트랜잭션 안에서 끝내고 DTO만 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class InstrumentNewsQueryReader {

	private final InstrumentService instrumentService;

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	/**
	 * 종목의 시장을 읽는다 — 조회 경로가 주식·코인으로 갈리는 첫 분기다.
	 *
	 * <p>없는 종목이면 {@code InstrumentService}가 404({@code NOT_FOUND})로 거절한다. 그 판정을 여기서 다시
	 * 하지 않는 것이 기존과 같다.
	 */
	@Transactional(readOnly = true)
	public Market readMarket(Long instrumentId) {
		return instrumentService.getInstrumentEntity(instrumentId).getMarket();
	}

	/**
	 * 그 시각에 노출 가능한 기사·공시를 모아 상한만큼 자른 응답 목록이다 (§C-2의 구간, §C-3의 공시 날짜,
	 * §C-5의 게이트).
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
	 * <p><b>이 목록은 캐시하지 않는다</b>(ADR-0015 §1). 상한이 {@code now}의 함수라 캐시하면 게이트가 늦게
	 * 열린다 — 매 요청 DB로 가는 것이 그 게이트의 구현이다.
	 *
	 * <p><b>뉴스와 공시를 같은 질의로 가져오지 않는다</b>(§C-3). 공시는 {@code published_at}이 접수일
	 * {@code 00:00:00}이라 datetime 구간에 태우면 {@code D-1} 접수분이 구간 시작보다 일러 빠지고, {@code D}
	 * 접수분은 {@code PRE_MARKET} 구간에 들어와 <b>개장 직후에 그날 장중 접수 공시가 새어 나간다.</b>
	 * {@code D} 접수분이 {@code FULL}에만 있는 것도 그래서다 — {@code FULL}은 15:30 이후에만 노출된다.
	 *
	 * <p>범위는 {@code InstrumentNewsSummaryService}의 요약 생성 구간과 하한·공시 규칙이 같고 뉴스 상한만
	 * 다르다. 상한이 다른 것이 이 API의 요지라 한 메서드로 합치지 않는다.
	 */
	@Transactional(readOnly = true)
	public List<NewsItem> readStockItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope, LocalTime now) {
		return NewsItemTruncator
			.truncateAndSort(collectVisibleItems(instrumentId, originTradeDate, scope, now),
				properties.maxItemsPerNewsList())
			.stream()
			.map(NewsItem::from)
			.toList();
	}

	/** 주식 요약 행 1건 — 캐시의 로더가 부른다. 행 존재 여부까지 담아야 §C-4 4·5번을 가를 수 있다. */
	@Transactional(readOnly = true)
	public SummaryTextLookupDto readStockSummary(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope) {
		return instrumentNewsSummaryRepository
			.findByInstrumentIdAndOriginTradeDateAndScope(instrumentId, originTradeDate, scope)
			.map(summary -> SummaryTextLookupDto.row(summary.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	/**
	 * 최근 24시간 코인 기사 목록 (FEED-008). 코인은 공시가 없어 뉴스만 모으고 노출 게이트도 없다 — 실시간이라
	 * 스포일러가 성립하지 않는다(§C-5).
	 *
	 * <p><b>창이 조회 시각 기준이라 캐시하지 않는다</b>(ADR-0015 §1) — 캐시하면 "{@code items}의 24시간 창은
	 * 조회 시각 기준"이라는 명문 계약(FEED-008)이 깨진다.
	 */
	@Transactional(readOnly = true)
	public List<NewsItem> readCryptoItems(Long instrumentId, LocalDateTime now) {
		return NewsItemTruncator
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
	}

	/** 코인 요약은 {@code generated_at} 최신 1행이다 (§C-9) — 오늘 날짜 행이 아니다. */
	@Transactional(readOnly = true)
	public SummaryTextLookupDto readLatestCryptoSummary(Long instrumentId) {
		return instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(instrumentId, NewsSummaryScope.ROLLING_24H)
			.map(summary -> SummaryTextLookupDto.row(summary.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

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
