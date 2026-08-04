// 종목 뉴스 요약 1건을 확정하는 서비스 — 구간 조회 → 절단 → 서술 → 저장까지가 이 클래스의 전부다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
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
 * <b>요약 1건을 확정하는 책임만 진다</b>(spec §C-6). 배치는 이것을 종목마다 부르기만 하며, 카드 확정을
 * {@code PriceMoveCardService}로 뺀 것과 같은 이유다 — 배치에 두면 오케스트레이션과 도메인 로직이 한 클래스에
 * 섞이고, 코인 배치(별도 이슈)가 같은 확정 경로를 재사용할 수 없다.
 *
 * <p><b>{@code NarrativeService} 하나만 주입한다</b>(§C-6). 요약은 2단계 경로라 후검증에 걸리면 재생성 1회,
 * 그래도 걸리면 서술 없음 + {@code NONE}이며 그 흐름은 이미 그 서비스 안에 있다.
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> 구간 조회와 서술 생성(외부 LLM 호출, 건당 최대 20초) 사이에
 * 경계를 두면 그 대기 내내 DB 커넥션을 쥔 채 종목 수만큼 반복하게 된다. 저장은 {@code save()} 한 번뿐이라
 * {@code SimpleJpaRepository}의 자체 트랜잭션으로 충분하다 — 카드처럼 두 테이블에 나눠 쓰지 않아
 * {@code PriceMoveCardWriter} 같은 별도 경계가 필요 없다.
 *
 * <p><b>쓰기는 {@code instrument_news_summaries} 하나뿐이다.</b> {@code instruments}·{@code market_news_items}는
 * 읽기만 한다 (8개 이슈 공통 조건인 원장 불변).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentNewsSummaryService {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	private final NarrativeService narrativeService;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	private final Clock clock;

	/**
	 * 주식 종목 1건의 요약을 확정해 저장한다.
	 *
	 * <p><b>중복 확인이 서술보다 먼저다.</b> 뒤로 미루면 재실행 때마다 종목 수만큼 LLM을 다시 부르고 그 결과를
	 * 유니크 제약이 버린다 ({@code PriceMoveCardService}와 같은 형태).
	 *
	 * <p><b>기사가 0건이면 만들지 않는다.</b> 조회는 그 상태를 §C-4 판정 순서 3번({@code 대상 기사·공시 0건})으로
	 * 읽어 행이 있든 없든 {@code EMPTY}로 내리므로, 행을 만들어도 화면이 달라지지 않고 <b>근거 없는 문장을 LLM이
	 * 지어낼 자리만 생긴다.</b> {@code UNAVAILABLE}과 구분해야 하는 것은 "기사는 있는데 서술이 없는" 경우이고,
	 * 그때는 아래에서 {@code summary}가 {@code null}인 행을 남긴다.
	 *
	 * @param scope {@code PRE_MARKET} 또는 {@code FULL}. 코인의 {@code ROLLING_24H}는 범위·저장 규칙이 모두
	 *     달라(§C-2·§C-9) 이 경로를 쓰지 않는다
	 * @return 저장된 요약. <b>기사가 0건이거나 이미 같은 요약이 있으면 {@code Optional.empty()}</b>이며 오류가 아니다
	 */
	public Optional<InstrumentNewsSummary> generateStockSummary(
		Instrument instrument, LocalDate originTradeDate, NewsSummaryScope scope) {
		if (instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			instrument.getId(), originTradeDate, scope)) {
			log.debug("이미 있는 요약이라 건너뛴다. 종목={} 거래일={} 범위={}",
				instrument.getId(), originTradeDate, scope);
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			collectItems(instrument.getId(), originTradeDate, scope), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("대상 기사가 없어 요약을 만들지 않는다. 종목={} 거래일={} 범위={}",
				instrument.getId(), originTradeDate, scope);
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolveNewsSummaryNarrative(
			new NewsSummaryPromptDto(
				instrument.getName(),
				scope,
				originTradeDate,
				items.stream().map(InstrumentNewsSummaryService::toSource).toList()));
		// 서술이 NONE이면 summary가 null인 행을 남긴다 (§C-4·§C-8). 행을 만들지 않으면 조회가 EMPTY와
		// UNAVAILABLE을 구분하지 못한다 — 기사가 있는데 서술만 실패한 상태가 "기사가 없다"로 보인다.
		return Optional.of(instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			instrument,
			originTradeDate,
			scope,
			narrative.narrative(),
			narrative.source(),
			LocalDateTime.now(clock))));
	}

	/**
	 * 그 범위의 기사·공시를 모은다 (§C-2의 구간, §C-3의 공시 날짜 판정).
	 *
	 * <pre>
	 * PRE_MARKET  뉴스 [D-1 15:30, D 09:00]   공시 rcept_dt = D-1
	 * FULL        뉴스 [D-1 15:30, D 15:30]   공시 rcept_dt ∈ {D-1, D}
	 * </pre>
	 *
	 * <p><b>뉴스와 공시를 같은 질의로 가져오지 않는다.</b> 공시를 같은 datetime 구간에 태우면 정확히 반대로
	 * 걸린다 — {@code D-1} 접수분은 {@code D-1 00:00:00}이라 구간 시작보다 이르러 <b>빠지고</b>, {@code D}
	 * 접수분은 {@code D 00:00:00}이라 {@code PRE_MARKET} 구간 안에 <b>들어오는데</b> 거기엔 {@code D} 장중
	 * 접수분이 섞여 있어 개장 전 요약에 그날 장중 공시가 새어 나간다 ({@code NewsMatcher}와 같은 이유).
	 *
	 * <p>{@code D} 접수 공시가 {@code FULL}에만 들어가는 것도 그래서다 — {@code FULL}은 15:30 이후에만
	 * 노출되므로 장중 유출이 성립하지 않는다(§C-3).
	 *
	 * <p>구간 경계 {@code 15:30}·{@code 09:00}은 <b>벽시계</b>이며 분봉을 찾는 값이 아니다 (§C-2-1,
	 * {@link MarketSessionTimes}). {@code D-1}은 {@link BusinessDayCalendar#previousBusinessDay}로 구한다 —
	 * 시장 단위 직전 거래일을 얻는 유일한 경로다(§C-6).
	 */
	private List<MarketNewsItem> collectItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		LocalDateTime newsFrom = LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME);
		LocalDateTime newsTo = switch (scope) {
			case PRE_MARKET -> LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME);
			case FULL -> LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_CLOSE_TIME);
			// 코인은 구간도 저장 규칙도 달라 이 경로를 쓰지 않는다. 조용히 주식 구간으로 만들면 재생 시간축이
			// 없는 종목에 원본 거래일 구간이 붙어 매일 0건이 되고 예외도 로그도 남지 않는다.
			case ROLLING_24H -> throw new IllegalArgumentException(
				"ROLLING_24H는 코인 요약의 범위라 주식 요약 경로에서 쓸 수 없습니다.");
		};

		List<MarketNewsItem> items = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId, MarketNewsItemType.NEWS, newsFrom, newsTo));
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

	// 프롬프트에는 제목·언론사·발행시각만 싣는다 — URL과 본문은 넘기지 않는다 (§정책 전제).
	private static NewsSourceDto toSource(MarketNewsItem item) {
		return new NewsSourceDto(
			item.getTitle(),
			item.getPublisher(),
			item.getPublishedAt(),
			item.getType() == MarketNewsItemType.DISCLOSURE);
	}
}
