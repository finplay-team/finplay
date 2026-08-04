// 개장 전 브리핑 1건을 확정하는 서비스 — 시장 단위 구간 조회 → 절단 → 서술 → 저장까지가 이 클래스의 전부다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <b>브리핑 1건을 확정하는 책임만 진다</b>(spec §C-6). 배치는 이것을 부르기만 한다 —
 * {@code InstrumentNewsSummaryService}·{@code PriceMoveCardService}와 같은 형태·같은 이유다.
 *
 * <p><b>{@code items}를 저장하지 않는다</b>(FEED-009·§데이터 모델). 저장하는 것은 문장과 그 출처뿐이고, 목록은
 * 조회 시 같은 구간 질의로 다시 만든다. 그래서 이 클래스가 모으는 기사 목록은 <b>프롬프트 입력 전용</b>이며
 * 상한도 응답 상한({@code max-items-per-briefing})이 아니라 <b>LLM 입력 상한
 * ({@code max-items-per-summary})</b>을 쓴다 (§C-7).
 *
 * <p><b>장중 기사를 절대 담지 않는다</b>(FEED-009). 구간이 §C-2의 {@code 전장} {@code [D-1 15:30, D 09:00]}이고
 * 공시는 {@code rcept_dt = D-1}이다(§C-3) — 이 범위가 실제 투자자가 아침에 아는 정보와 같다.
 *
 * <p>트랜잭션을 열지 않는 이유와 쓰기 범위({@code market_briefings} 하나뿐)는
 * {@code InstrumentNewsSummaryService}와 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBriefingService {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final MarketBriefingRepository marketBriefingRepository;

	private final NarrativeService narrativeService;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	private final Clock clock;

	/**
	 * 주식 시장의 개장 전 브리핑을 확정해 저장한다. {@code (시장, 원본 거래일)} 단위로 1건이며 전 회원이 공유한다.
	 *
	 * <p>중복 확인을 서술보다 먼저 하는 이유, 기사 0건이면 만들지 않는 이유, 서술이 {@code NONE}이어도 행을
	 * 남기는 이유는 {@code InstrumentNewsSummaryService.generateStockSummary}와 같다.
	 *
	 * <p><b>배포 직후 이틀은 비어 있을 수 있다</b>(FEED-009). 근거 구간이 과거 17.5시간이라 수집 이력이 쌓여야
	 * 한다 — 정상 동작이며 오류가 아니다.
	 *
	 * @return 저장된 브리핑. <b>기사가 0건이거나 이미 그 거래일 브리핑이 있으면 {@code Optional.empty()}</b>다
	 */
	public Optional<MarketBriefing> generateStockBriefing(LocalDate originTradeDate) {
		if (marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, originTradeDate)) {
			log.debug("이미 있는 브리핑이라 건너뛴다. 거래일={}", originTradeDate);
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			collectPreMarketItems(originTradeDate), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("전장 구간 기사가 없어 브리핑을 만들지 않는다. 거래일={}", originTradeDate);
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolveMarketBriefingNarrative(
			new MarketBriefingPromptDto(
				Market.STOCK, originTradeDate, items.stream().map(MarketBriefingService::toPromptItem).toList()));
		return Optional.of(marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK,
			originTradeDate,
			narrative.narrative(),
			narrative.source(),
			LocalDateTime.now(clock))));
	}

	/**
	 * {@code 전장} 구간의 기사와 {@code rcept_dt = D-1} 공시를 시장 전체에서 모은다 (§C-2·§C-3).
	 *
	 * <p><b>종목별로 나눠 묻지 않는다</b>(§C-2-1). 상한을 시장 전체 목록에 걸어야 §뉴스 매칭 범위의 절단이
	 * 의도대로 작동한다 — 종목별로 자른 뒤 합치면 종목당 상한이 되어 전체가 상한의 몇 배로 불어난다.
	 *
	 * <p>뉴스와 공시를 같은 질의로 가져오지 않는 이유는 {@code InstrumentNewsSummaryService}와 같다.
	 * 구간 경계는 <b>벽시계</b>이며({@link MarketSessionTimes}) 분봉 시각이 아니다 — 브리핑은 시장 단위 단일
	 * 질의라 애초에 종목별 분봉 시각을 하한으로 쓸 수 없다.
	 */
	private List<MarketNewsItem> collectPreMarketItems(LocalDate originTradeDate) {
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

	// 브리핑은 시장 단위 단일 목록이라 어느 종목 소식인지 모델이 알 수 없다 — 기사마다 종목명을 붙인다.
	private static BriefingNewsItemDto toPromptItem(MarketNewsItem item) {
		return new BriefingNewsItemDto(
			item.getInstrument().getName(),
			new NewsSourceDto(
				item.getTitle(),
				item.getPublisher(),
				item.getPublishedAt(),
				item.getType() == MarketNewsItemType.DISCLOSURE));
	}
}
