package com.finplay.api.domain.feedback.store;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryService;
import com.finplay.api.domain.feedback.service.MarketBriefingService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
abstract class FeedbackQueryCacheWiringSupport {

	protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

	protected static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	protected static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	protected static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

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

	@Autowired
	protected TestClock clock;

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
		clock.set(NOW);
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


	protected int stockSummaryRowCallsAcrossThreeQueries() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockSummary("전장 기사가 이어졌습니다.");
		clearInvocations(instrumentNewsSummaryRepository);

		for (int attempt = 0; attempt < 3; attempt++) {
			instrumentNewsQueryService.getInstrumentNews(stock.getId());
		}
		return callsTo(instrumentNewsSummaryRepository, "findByInstrumentIdAndOriginTradeDateAndScope");
	}

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

	protected int cryptoBriefingTextCallsOnASecondQuery() {
		saveCryptoNews("코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveCryptoBriefing("최근 24시간 코인 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.CRYPTO);
		clearInvocations(marketBriefingRepository);

		marketBriefingService.getBriefing(Market.CRYPTO);
		return callsTo(marketBriefingRepository, "findFirstByMarketOrderByGeneratedAtDescIdDesc");
	}

	protected int cryptoBriefingItemCallsOnASecondQuery() {
		saveCryptoNews("코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveCryptoBriefing("최근 24시간 코인 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.CRYPTO);
		clearInvocations(marketNewsItemRepository);

		marketBriefingService.getBriefing(Market.CRYPTO);
		return callsTo(marketNewsItemRepository, "findMarketNewsPublishedBetween");
	}

	private static int callsTo(Object spy, String methodName) {
		return (int)mockingDetails(spy).getInvocations().stream()
			.filter(invocation -> invocation.getMethod().getName().equals(methodName))
			.count();
	}

}
