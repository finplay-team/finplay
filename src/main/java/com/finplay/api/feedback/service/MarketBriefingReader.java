// 개장 전 브리핑의 생성·조회가 공유하는 DB 읽기를 각각 한 트랜잭션 안에서 끝내는 읽기 전용 컴포넌트.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code MarketBriefingService}에서 <b>DB에 닿는 읽기만</b> 떼어 낸 것이다. 이유는
 * {@code InstrumentNewsQueryReader}와 같다 — 조회 캐시의 락 대기(최대
 * {@code feedback.query-cache.wait-millis})가 JDBC 커넥션을 쥔 채로 돌면, 만료 경계에 요청이 몰리는 바로 그
 * 순간 대기 스레드가 커넥션 풀을 채워 뒤따르는 요청이 커넥션 획득에서 막힌다(PR #257 남은 위험 1).
 *
 * <p><b>구간 질의를 생성 경로와 여기서 공유한다</b>(§C-6). 브리핑은 {@code items}를 저장하지 않고 조회 시
 * 다시 만들기 때문에 생성과 조회가 같은 구간을 봐야 하며, 나누면 그 질의가 두 벌이 된다. 그래서 절단 전
 * 목록을 돌려주는 {@code collect*}(생성 경로용)와 절단·매핑까지 끝낸 {@code read*}(조회 경로용)를 한 클래스에
 * 둔다 — <b>상한만 다르다</b>(생성 {@code max-items-per-summary}, 조회 {@code max-items-per-briefing}, §C-7).
 *
 * <p>{@code collect*}가 엔티티를 돌려주는 것은 생성 경로가 프롬프트에 종목명을 붙여야 하기 때문이다. 두 질의
 * 모두 {@code JOIN FETCH n.instrument}라 트랜잭션이 닫힌 뒤에도 종목이 초기화돼 있다(이 클래스로 옮기기 전과
 * 같은 성질이다). <b>조회 경로로 나가는 값에는 엔티티가 없다</b> — {@code read*}가 경계 안에서 DTO로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class MarketBriefingReader {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final MarketBriefingRepository marketBriefingRepository;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	/**
	 * 조회 응답의 {@code items} — 전장 구간 목록을 {@code max-items-per-briefing}으로 자른 것이다.
	 *
	 * <p><b>이 목록이 시각 비의존인 유일한 목록이라 캐시 대상이다</b>(ADR-0015 §1). 구간이
	 * {@code [D-1 15:30, D 09:00]} 고정이라 조회 시각이 언제든 같은 값이다(FEED-009 — 장중 기사를 절대 담지
	 * 않는다).
	 */
	@Transactional(readOnly = true)
	public List<BriefingNewsItem> readStockBriefingItems(LocalDate originTradeDate) {
		return NewsItemTruncator
			.truncateAndSort(collectPreMarketItems(originTradeDate), properties.maxItemsPerBriefing())
			.stream()
			.map(BriefingNewsItem::from)
			.toList();
	}

	/** 주식 브리핑 행 1건 — 캐시의 로더가 부른다. 행 존재 여부까지 담아야 §C-4 4·5번을 가를 수 있다. */
	@Transactional(readOnly = true)
	public SummaryTextLookupDto readStockBriefingText(LocalDate originTradeDate) {
		return marketBriefingRepository.findByMarketAndOriginTradeDate(Market.STOCK, originTradeDate)
			.map(briefing -> SummaryTextLookupDto.row(briefing.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	/**
	 * 코인 조회 응답의 {@code items} — 최근 24시간 목록을 같은 상한으로 자른 것이다.
	 *
	 * <p><b>창이 조회 시각 기준이라 캐시하지 않는다</b>(FEED-008·ADR-0015 §1).
	 */
	@Transactional(readOnly = true)
	public List<BriefingNewsItem> readCryptoBriefingItems(LocalDateTime now) {
		return NewsItemTruncator
			.truncateAndSort(collectRollingItems(now), properties.maxItemsPerBriefing())
			.stream()
			.map(BriefingNewsItem::from)
			.toList();
	}

	/** 코인 브리핑은 {@code generated_at} 최신 1행이다 (§C-9) — 오늘 날짜 행이 아니다. */
	@Transactional(readOnly = true)
	public SummaryTextLookupDto readLatestCryptoBriefingText() {
		return marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO)
			.map(briefing -> SummaryTextLookupDto.row(briefing.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	/**
	 * {@code 전장} 구간의 기사와 {@code rcept_dt = D-1} 공시를 시장 전체에서 모은다 (§C-2·§C-3).
	 * <b>절단 전 목록이다</b> — 상한은 호출부가 건다(생성과 조회가 다른 값을 쓰기 때문이다, §C-7).
	 *
	 * <p><b>종목별로 나눠 묻지 않는다</b>(§C-2-1). 상한을 시장 전체 목록에 걸어야 §뉴스 매칭 범위의 절단이
	 * 의도대로 작동한다 — 종목별로 자른 뒤 합치면 종목당 상한이 되어 전체가 상한의 몇 배로 불어난다.
	 *
	 * <p>뉴스와 공시를 같은 질의로 가져오지 않는 이유는 {@code InstrumentNewsSummaryService}와 같다.
	 * 구간 경계는 <b>벽시계</b>이며({@link MarketSessionTimes}) 분봉 시각이 아니다 — 브리핑은 시장 단위 단일
	 * 질의라 애초에 종목별 분봉 시각을 하한으로 쓸 수 없다.
	 */
	@Transactional(readOnly = true)
	public List<MarketNewsItem> collectPreMarketItems(LocalDate originTradeDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		List<MarketNewsItem> items = new ArrayList<>(marketNewsItemRepository.findMarketNewsPublishedBetween(
			Market.STOCK,
			LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
			LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME)));
		items.addAll(marketNewsItemRepository.findMarketDisclosuresReceivedOn(
			Market.STOCK,
			previousTradingDate.atStartOfDay(),
			previousTradingDate.plusDays(1).atStartOfDay()));
		return items;
	}

	/**
	 * 최근 24시간 코인 기사 (§C-2의 {@code ROLLING_24H}). 코인은 공시가 없어 뉴스만 모은다.
	 * <b>절단 전 목록이다</b> — 상한은 호출부가 건다.
	 *
	 * <p>창 길이는 {@link MarketSessionTimes#ROLLING_WINDOW}다 — 생성과 조회가 같은 값을 봐야 요약이 다루는
	 * 창과 화면 목록의 창이 갈리지 않는다.
	 */
	@Transactional(readOnly = true)
	public List<MarketNewsItem> collectRollingItems(LocalDateTime now) {
		return marketNewsItemRepository.findMarketNewsPublishedBetween(
			Market.CRYPTO, now.minus(MarketSessionTimes.ROLLING_WINDOW), now);
	}
}
