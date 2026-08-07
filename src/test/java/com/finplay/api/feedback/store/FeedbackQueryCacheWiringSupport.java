// 조회 캐시 배선 대조 테스트 두 갈래(enabled=true/false)가 공유하는 픽스처와 시나리오 — 시나리오를 여기 한 벌만 두어 두 갈래가 갈리지 않게 한다.
package com.finplay.api.feedback.store;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.service.InstrumentNewsQueryService;
import com.finplay.api.feedback.service.MarketBriefingService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * tasks.md 항목 3·4 — <b>대조 대상은 캐시다.</b> 두 하위 클래스가 {@code feedback.query-cache.enabled}만 다르게
 * 두고 같은 시나리오를 돌린다(항목 6의 mock {@code RedisLock} 대조군과 다르다).
 *
 * <p><b>시나리오를 하위 클래스에 복사하지 않고 여기 한 벌만 둔다.</b> 두 갈래에 같은 코드를 두 번 적으면 한쪽만
 * 고쳐졌을 때 "N회 대 1회"가 서로 다른 상황을 비교하게 되는데, 그때도 두 테스트는 각자 초록이다 — 대조가
 * 조용히 무의미해지는 형태다. 하위 클래스는 <b>기대 숫자만</b> 갖는다.
 *
 * <p><b>Redis 키를 매 테스트 전후로 지운다.</b> Testcontainers Redis는 실행 전체가 공유하는 싱글턴이고 DB만
 * {@code @Transactional}로 롤백되므로, 지우지 않으면 앞 테스트가 남긴 값이 뒤 테스트의 "원본 N회" 단정을
 * 조용히 무너뜨린다(항목 6의 tasks.md 지침과 같은 이유).
 */
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, FeedbackQueryCacheWiringSupport.FixedClockTestConfig.class})
abstract class FeedbackQueryCacheWiringSupport {

	protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 원본 거래일 D = 2026-08-05(수), 직전 영업일 D-1 = 2026-08-04(화), 서비스 날짜 = 2026-08-06(목).
	// 기존 게이트 테스트들과 같은 달력을 쓴다.
	protected static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	protected static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	protected static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	// 10:00 — 09:00 게이트는 열렸고 15:30 전이라 주식 요약 scope는 PRE_MARKET이다.
	protected static final LocalDateTime NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	protected static final String STOCK_SYMBOL = "005930";

	protected static final String STOCK_BRIEFING_ITEMS_KEY_PREFIX = "feedback:query-cache:v1:stock-briefing-items:";

	@Autowired
	protected InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	protected MarketBriefingService marketBriefingService;

	@Autowired
	protected InstrumentService instrumentService;

	@Autowired
	protected InstrumentRepository instrumentRepository;

	@Autowired
	protected StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	protected StringRedisTemplate redisTemplate;

	// 원본(DB) 호출 횟수를 세는 것이 이 테스트의 관찰점이다 — 실제 빈을 그대로 쓰면서 호출만 센다.
	@MockitoSpyBean
	protected MarketNewsItemRepository marketNewsItemRepository;

	@MockitoSpyBean
	protected InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@MockitoSpyBean
	protected MarketBriefingRepository marketBriefingRepository;

	protected Instrument stock;

	protected Instrument crypto;

	@BeforeEach
	void setUpFixtures() {
		clearQueryCacheKeys();
		stock = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		crypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO,
			"CACHE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
			"캐시코인",
			BigDecimal.ONE,
			5000L,
			true,
			NOW));
		stockReplaySessionRepository.save(StockReplaySession.ready(
			SERVICE_DATE,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40)),
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
	}

	@AfterEach
	void cleanUpQueryCacheKeys() {
		clearQueryCacheKeys();
	}

	// 정리 로직은 FeedbackQueryCacheTestKeys에 있다 — 기존 게이트 테스트들도 상속 없이 같은 것을 쓴다.
	protected void clearQueryCacheKeys() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	protected void saveStockNews(String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			stock, MarketNewsItemType.NEWS, title, "테스트경제",
			"https://news.example.test/cache/" + title, publishedAt, NOW));
	}

	protected void saveCryptoNews(String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			crypto, MarketNewsItemType.NEWS, title, "테스트경제",
			"https://news.example.test/cache/crypto/" + title, publishedAt, NOW));
	}

	protected void saveStockSummary(String text) {
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	protected void saveCryptoSummary(String text) {
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			crypto, SERVICE_DATE, NewsSummaryScope.ROLLING_24H, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 5))));
	}

	protected void saveStockBriefing(String text) {
		marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	protected void saveCryptoBriefing(String text) {
		marketBriefingRepository.save(MarketBriefing.create(
			Market.CRYPTO, SERVICE_DATE, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 5))));
	}

	// ── 시나리오 (두 갈래가 공유한다) ────────────────────────────────────────────────

	/** 주식 요약 조회 3번 — 요약 행 조회가 몇 번 일어났는지. */
	protected int stockSummaryRowCallsAcrossThreeQueries() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockSummary("전장 기사가 이어졌습니다.");
		clearInvocations(instrumentNewsSummaryRepository);

		for (int attempt = 0; attempt < 3; attempt++) {
			instrumentNewsQueryService.getInstrumentNews(stock.getId());
		}
		return callsTo(instrumentNewsSummaryRepository, "findByInstrumentIdAndOriginTradeDateAndScope");
	}

	/** 코인 요약 조회 3번 — 요약 행 조회가 몇 번 일어났는지. */
	protected int cryptoSummaryRowCallsAcrossThreeQueries() {
		saveCryptoNews("코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveCryptoSummary("최근 24시간 기사가 이어졌습니다.");
		clearInvocations(instrumentNewsSummaryRepository);

		for (int attempt = 0; attempt < 3; attempt++) {
			instrumentNewsQueryService.getInstrumentNews(crypto.getId());
		}
		return callsTo(
			instrumentNewsSummaryRepository, "findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc");
	}

	/**
	 * 주식 요약 조회 3번에서 {@code items} 수집이 몇 번 일어났는지. <b>이쪽은 캐시하지 않는 것이 요구사항이다</b>
	 * — §C-5 노출 게이트의 구현이라 캐시하면 게이트가 늦게 열린다(ADR-0015 §1).
	 */
	protected int stockSummaryItemCallsAcrossThreeQueries() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockSummary("전장 기사가 이어졌습니다.");
		clearInvocations(marketNewsItemRepository);

		for (int attempt = 0; attempt < 3; attempt++) {
			instrumentNewsQueryService.getInstrumentNews(stock.getId());
		}
		return callsTo(
			marketNewsItemRepository, "findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc");
	}

	/**
	 * 캐시가 채워진 뒤의 <b>두 번째</b> 주식 브리핑 조회 1건이 DB를 몇 번 부르는지 — 텍스트 1 + items 2(뉴스·공시)로
	 * 캐시 대상이 셋이다. 적중이면 0이어야 한다.
	 */
	protected int stockBriefingDbCallsOnASecondQuery() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.STOCK);
		clearInvocations(marketNewsItemRepository, marketBriefingRepository);

		marketBriefingService.getBriefing(Market.STOCK);
		return callsTo(marketBriefingRepository, "findByMarketAndOriginTradeDate")
			+ callsTo(marketNewsItemRepository, "findMarketNewsPublishedBetween")
			+ callsTo(marketNewsItemRepository, "findMarketDisclosuresReceivedOn");
	}

	/** 캐시가 채워진 뒤의 두 번째 코인 브리핑 조회에서 브리핑 <b>텍스트</b> 행 조회가 몇 번 일어났는지. */
	protected int cryptoBriefingTextCallsOnASecondQuery() {
		saveCryptoNews("코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveCryptoBriefing("최근 24시간 코인 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.CRYPTO);
		clearInvocations(marketBriefingRepository);

		marketBriefingService.getBriefing(Market.CRYPTO);
		return callsTo(marketBriefingRepository, "findFirstByMarketOrderByGeneratedAtDescIdDesc");
	}

	/**
	 * 같은 두 번째 코인 브리핑 조회에서 {@code items}(24시간 창) 수집이 몇 번 일어났는지. <b>코인 items는 캐시
	 * 대상이 아니므로 캐시를 켜도 줄지 않아야 한다</b>(ADR-0015 §1, tasks.md 항목 4).
	 */
	protected int cryptoBriefingItemCallsOnASecondQuery() {
		saveCryptoNews("코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveCryptoBriefing("최근 24시간 코인 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.CRYPTO);
		clearInvocations(marketNewsItemRepository);

		marketBriefingService.getBriefing(Market.CRYPTO);
		return callsTo(marketNewsItemRepository, "findMarketNewsPublishedBetween");
	}

	// 메서드 이름으로 호출 횟수를 센다 — verify(times(n))는 기대값을 미리 박아야 해서 "관찰한 수를 하위 클래스가
	// 단정한다"는 이 구조에 쓸 수 없다.
	private static int callsTo(Object spy, String methodName) {
		return (int)mockingDetails(spy).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals(methodName))
			.count();
	}

	@TestConfiguration
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}
	}
}
