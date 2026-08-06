// 개장 전 브리핑의 생성(배치)과 조회(API)를 함께 갖는 서비스 — 둘이 같은 구간 질의를 공유한다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.store.FeedbackQueryCache;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <b>생성과 조회를 한 클래스가 갖는다</b>(spec §C-6, 명문화됨). 종목 뉴스 요약이 생성
 * ({@code InstrumentNewsSummaryService})과 조회({@code InstrumentNewsQueryService})로 나뉜 것과 비대칭이지만,
 * 브리핑은 <b>두 경로가 같은 구간 질의를 공유</b>해서 나누면 그 질의가 두 벌이 된다 — {@code items}를 저장하지
 * 않고 조회 시 다시 만들기 때문이다(FEED-009·§데이터 모델). 배치는 생성 메서드를 부르기만 한다.
 *
 * <p><b>상한 두 개가 이 클래스 안에 공존한다</b>(§C-7). 같은 목록을 서로 다른 값으로 자른다.
 *
 * <pre>
 * 생성(LLM 입력)  max-items-per-summary
 * 조회(응답 items) max-items-per-briefing
 * </pre>
 *
 * <b>바꿔 쓰면 조용히 틀린다</b> — 예외도 로그도 없이 프롬프트에 실리는 기사 수나 화면 목록 길이만 달라진다.
 *
 * <p><b>장중 기사를 절대 담지 않는다</b>(FEED-009). 구간이 §C-2의 {@code 전장} {@code [D-1 15:30, D 09:00]}이고
 * 공시는 {@code rcept_dt = D-1}이다(§C-3) — 이 범위가 실제 투자자가 아침에 아는 정보와 같다. 조회 시각이
 * 언제든 이 구간이므로 Part C처럼 상한이 재생 시각을 따라 넓어지지 않는다.
 *
 * <p>생성 경로가 트랜잭션을 열지 않는 이유와 쓰기 범위({@code market_briefings} 하나뿐)는
 * {@code InstrumentNewsSummaryService}와 같다. <b>조회 경로는 아무것도 쓰지 않는다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBriefingService {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final MarketBriefingRepository marketBriefingRepository;

	private final StockReplayService stockReplayService;

	private final NarrativeService narrativeService;

	// DB 읽기는 전부 이 컴포넌트가 자기 트랜잭션 안에서 끝낸다 — 조회 경로의 캐시 대기가 커넥션을 쥐지 않게
	// 하려는 것이며(PR #257 남은 위험 1), 생성·조회가 같은 구간 질의를 공유하는 성질(§C-6)도 그대로 유지된다.
	private final MarketBriefingReader marketBriefingReader;

	// 조회 경로가 읽고, 코인 갱신 경로가 갱신 성공 시에만 무효화한다(ADR-0015 §3). 생성 경로는 이 캐시를
	// **읽지** 않는다 — 절단 상한이 조회(max-items-per-briefing)와 생성(max-items-per-summary)이 달라서다(§C-7).
	// 주식은 무효화도 하지 않는다 — generateStockBriefing이 이미 있는 행을 건너뛰어 값이 그날 안 바뀐다.
	private final FeedbackQueryCache feedbackQueryCache;

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
			marketBriefingReader.collectPreMarketItems(originTradeDate), properties.maxItemsPerSummary());
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
	 * 코인 시장 브리핑을 갱신한다 — 매시 코인 배치가 부른다 (FEED-009).
	 *
	 * <p>주식과 다른 셋(한 범위·UPSERT·{@code created_at} 기준 재생성 판정)과 "직전 생성"을 오늘 행이 아니라
	 * {@code generated_at} 최신 행으로 잡는 이유는 {@code InstrumentNewsSummaryService.refreshCryptoSummary}와
	 * 같다. 코인은 '개장 전'이 없어 주기 갱신이 그 자리를 대신한다.
	 *
	 * @return 저장·갱신된 브리핑. <b>새 기사가 없거나 창 안 기사가 0건이면 {@code Optional.empty()}</b>다
	 */
	public Optional<MarketBriefing> refreshCryptoBriefing() {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<MarketBriefing> latest = marketBriefingRepository
			.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO);
		if (latest.isPresent()
			&& !marketNewsItemRepository.existsCollectedAfter(Market.CRYPTO, latest.get().getGeneratedAt())) {
			log.debug("직전 생성 이후 수집된 코인 기사가 없어 브리핑을 다시 만들지 않는다. 직전생성={}",
				latest.get().getGeneratedAt());
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			marketBriefingReader.collectRollingItems(now), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("최근 24시간 코인 기사가 없어 브리핑을 만들지 않는다.");
			return Optional.empty();
		}

		LocalDate batchDate = now.toLocalDate();
		NarrativeResultDto narrative = narrativeService.resolveMarketBriefingNarrative(
			new MarketBriefingPromptDto(
				Market.CRYPTO, batchDate, items.stream().map(MarketBriefingService::toPromptItem).toList()));
		MarketBriefing saved = marketBriefingRepository.save(
			marketBriefingRepository.findByMarketAndOriginTradeDate(Market.CRYPTO, batchDate)
				.map(row -> {
					row.refreshNarrative(narrative.narrative(), narrative.source(), now);
					return row;
				})
				.orElseGet(() -> MarketBriefing.create(
					Market.CRYPTO, batchDate, narrative.narrative(), narrative.source(), now)));
		// 저장 뒤에 지운다 — 먼저 지우면 그 사이 들어온 조회가 옛 행을 다시 캐시해 갱신이 묻힌다. 위에서
		// Optional.empty()로 빠져나간 실행(새 기사 없음·창 안 기사 0건)은 값이 안 바뀌었으므로 지우지 않는다.
		// 무효화 실패(Redis 장애)는 evict가 삼켜 배치를 죽이지 않는다(ADR-0015 §3).
		feedbackQueryCache.evictCryptoBriefingText();
		return Optional.of(saved);
	}

	/**
	 * 시장 단위 브리핑을 조회한다. 계약은 {@code docs/api-contracts.md}의 "개장 전 브리핑 조회" 행이다.
	 *
	 * <p><b>판정 순서는 §C-4의 표 그대로다.</b> 1번이 {@code EMPTY}이고 2번이 {@code NOT_YET}인 것이
	 * Part C와 갈리는 자리이며 <b>의도된 차이다</b> — Part D는 "브리핑이 아예 없는 날"이 정상이고, Part C는
	 * "아직 열리지 않았다"가 맞다. 두 조건이 동시에 성립하는 구간(00:00~08:40)이 있으므로 순서를 바꾸면 값이 갈린다.
	 *
	 * <pre>
	 * 1. 재생세션 미준비 → EMPTY,   originTradeDate=null
	 * 2. 09:00 이전     → NOT_YET, originTradeDate 채움
	 * 3. 기사 0건       → EMPTY,   items=[]
	 * 4. 브리핑 행 없음 → EMPTY,   items 채움
	 * 5. 행은 있고 summary가 null → UNAVAILABLE, items 채움
	 * 6. 그 외          → READY
	 * </pre>
	 *
	 * <p><b>09:00 하한을 Part C와 같게 맞춘다</b>(FEED-008·FEED-009). 두 API가 같은 전장 기사군을 다루므로
	 * 한쪽에만 하한이 있으면 08:41에 그쪽으로 조회해 다른 쪽이 09:00까지 감추는 기사를 먼저 볼 수 있다.
	 * 두 경로 모두 {@link MarketSessionTimes}의 같은 상수를 본다.
	 *
	 * <p><b>이 경로는 쓰지 않는다</b> — 브리핑은 배치 산출물이고 GET은 LLM을 호출하지도 DB에 쓰지도 않는다
	 * ({@code docs/conventions.md}, FEED-009).
	 *
	 * <p><b>{@code @Transactional}을 걸지 않는 것이 설계다.</b> DB 읽기는 {@link MarketBriefingReader}가 각각
	 * 자기 트랜잭션에서 끝내고, 이 메서드에는 조회 캐시의 락 대기(최대 {@code wait-millis})만 남는다 — 전체를
	 * 한 트랜잭션으로 감싸면 그 대기 동안 JDBC 커넥션을 쥐어, 만료 경계에 요청이 몰리는 순간 대기 스레드가
	 * 풀을 채우고 뒤따르는 요청이 커넥션 획득에서 막힌다({@code PostSellFeedbackService}가 LLM 호출을 트랜잭션
	 * 밖에 둔 것과 같은 형태다).
	 */
	public MarketBriefingResponse getBriefing(Market market) {
		if (market == Market.CRYPTO) {
			return getCryptoBriefing();
		}

		// 1번 — 원본 거래일 자체가 확정되지 않아 날짜를 지어낼 수 없다. NOT_YET이 아니라 EMPTY다.
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return MarketBriefingResponse.withoutItems(market, null, FeedbackContentStatus.EMPTY);
		}

		// 2번 — 세션은 준비됐고 개장 전이다. 여기서만 원본 거래일을 채운다.
		LocalDate originTradeDate = session.sourceTradingDate();
		if (LocalTime.now(clock).isBefore(MarketSessionTimes.MARKET_OPEN_TIME)) {
			return MarketBriefingResponse.withoutItems(
				market, originTradeDate, FeedbackContentStatus.NOT_YET);
		}

		// items는 저장하지 않으므로 생성 때와 같은 구간 질의로 다시 만든다 (FEED-009).
		// 상한만 다르다 — 여기는 응답 목록이라 max-items-per-briefing이고, 위 생성 경로는 LLM 입력이라
		// max-items-per-summary다. 두 값이 이 클래스 안에 공존하므로 바꿔 쓰지 않도록 주의한다(§C-7).
		//
		// 이 목록만 캐시를 거친다 — 구간이 [D-1 15:30, D 09:00] 고정이라 시각 비의존인 유일한 목록이다
		// (ADR-0015 §1). 절단 상한이 키에 들어가므로 위 상한을 바꾸면 캐시 키가 자연히 갈린다.
		// **생성 경로(generateStockBriefing)는 이 캐시를 보지 않는다** — 상한이 다르다.
		List<BriefingNewsItem> items = feedbackQueryCache.getOrLoadStockBriefingItems(
			originTradeDate, () -> marketBriefingReader.readStockBriefingItems(originTradeDate));

		// 3번 — 기사가 0건이면 행이 있든 없든 EMPTY다. 행 조회보다 앞이라 순서를 바꾸면
		// "기사도 없고 서술도 없는" 날이 UNAVAILABLE로 보인다.
		if (items.isEmpty()) {
			return MarketBriefingResponse.withoutItems(
				market, originTradeDate, FeedbackContentStatus.EMPTY);
		}

		// 4·5·6번 — 브리핑 텍스트도 캐시를 거친다. 캐시는 서술이 있는 값(READY)만 담으므로 적중은 곧 6번이고,
		// 미적중이면 로더가 반드시 실행돼 그 DB 결과로 4·5번을 지금 로직 그대로 가른다(§C-4 판정 순서 불변).
		AtomicBoolean briefingRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadStockBriefingText(originTradeDate, () -> {
			SummaryTextLookupDto lookup = marketBriefingReader.readStockBriefingText(originTradeDate);
			briefingRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return MarketBriefingResponse.of(
				market, originTradeDate, FeedbackContentStatus.READY, text.get(), items);
		}

		return MarketBriefingResponse.of(
			market,
			originTradeDate,
			briefingRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	/**
	 * 코인 브리핑 조회 — 최근 24시간 기사와 {@code generated_at} 최신 1행이다 (FEED-009).
	 *
	 * <p><b>주식의 게이트·판정 순서를 타지 않는다.</b> 코인은 재생세션과 무관하고 '개장 전'이라는 시점이 없어
	 * §C-4의 1·2번(미준비·개장 전)이 성립하지 않는다 — {@code NOT_YET}이 되지 않는다. 3~6번은 그대로 쓴다.
	 *
	 * <pre>
	 * 3. 기사 0건       → EMPTY,   items=[]
	 * 4. 브리핑 행 없음 → EMPTY,   items 채움
	 * 5. summary가 null → UNAVAILABLE, items 채움
	 * 6. 그 외          → READY
	 * </pre>
	 *
	 * <p><b>{@code originTradeDate}는 {@code null}로 내린다</b>(§C-9·§데이터 모델). 저장된 행에는 값이 있지만
	 * 그것은 유니크 축을 성립시키려고 채운 <b>배치 실행 날짜</b>이지 거래일이 아니라, 그대로 노출하면 화면이
	 * 재생 거래일로 오해한다.
	 *
	 * <p><b>{@code items}의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 최대 65분 어긋난다 —
	 * 허용된 동작이다</b>(FEED-008). 코인은 실시간이라 미래 정보가 아니므로 스포일러가 아니고, 맞추려면 조회 시
	 * 생성으로 되돌아가야 한다.
	 */
	private MarketBriefingResponse getCryptoBriefing() {
		List<BriefingNewsItem> items = marketBriefingReader.readCryptoBriefingItems(LocalDateTime.now(clock));
		if (items.isEmpty()) {
			return MarketBriefingResponse.withoutItems(Market.CRYPTO, null, FeedbackContentStatus.EMPTY);
		}

		// 코인은 텍스트만 캐시한다 — items의 24시간 창은 조회 시각 기준이라(FEED-008) 캐시 대상이 아니다.
		AtomicBoolean briefingRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadCryptoBriefingText(() -> {
			SummaryTextLookupDto lookup = marketBriefingReader.readLatestCryptoBriefingText();
			briefingRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return MarketBriefingResponse.of(
				Market.CRYPTO, null, FeedbackContentStatus.READY, text.get(), items);
		}

		return MarketBriefingResponse.of(
			Market.CRYPTO,
			null,
			briefingRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
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
