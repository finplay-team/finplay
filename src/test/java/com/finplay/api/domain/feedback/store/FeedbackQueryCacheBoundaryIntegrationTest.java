package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryReader;
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryService;
import com.finplay.api.domain.feedback.service.MarketBriefingReader;
import com.finplay.api.domain.feedback.service.MarketBriefingService;
import com.finplay.api.domain.feedback.service.NarrativeService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.lock.RedisLock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheBoundaryIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";

	private static final String PRE_MARKET_TEXT = "개장 전까지의 기사만 다룬 요약입니다.";

	private static final String FULL_TEXT = "장 마감까지 하루 전체를 다룬 요약입니다.";

	private static final List<String> LEDGER_TABLES = List.of(
		"orders", "trades", "accounts", "holdings", "holding_lots", "trade_allocations");

	@Autowired
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	private MarketBriefingService marketBriefingService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private StockReplayService stockReplayService;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	@Autowired
	private NarrativeService narrativeService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FeedbackNewsProperties newsProperties;

	@Autowired
	private FeedbackQueryCacheProperties cacheProperties;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private Instrument stock;

	private Instrument crypto;

	@BeforeEach
	void setUp() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
		mutableClock = clock;
		mutableClock.set(INITIAL_NOW);
		stock = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		crypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO,
			"BOUND" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
			"경계코인", BigDecimal.ONE, 5000L, true, INITIAL_NOW));
		stockReplaySessionRepository.save(StockReplaySession.ready(
			SERVICE_DATE,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40)),
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
	}

	private void saveNews(Instrument instrument, String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, title, "테스트경제",
			"https://news.example.test/boundary/" + instrument.getSymbol() + "/" + title,
			publishedAt, INITIAL_NOW));
	}

	private void saveStockSummary(NewsSummaryScope scope, String text) {
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, scope, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	private String stockSummaryKey(NewsSummaryScope scope) {
		return "feedback:query-cache:v1:stock-summary:" + stock.getId() + ":" + ORIGIN_TRADE_DATE + ":" + scope.name();
	}

	private String cryptoSummaryKey() {
		return "feedback:query-cache:v1:crypto-summary:" + crypto.getId();
	}

	@Test
	@DisplayName("15:29와 15:31의 두 조회가 같은 종목·같은 거래일인데도 서로 다른 요약을 본다")
	void theTwoQueriesAcrossTheCloseSeeDifferentSummariesBecauseScopeIsInTheKey() {
		saveNews(stock, "전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockSummary(NewsSummaryScope.PRE_MARKET, PRE_MARKET_TEXT);
		saveStockSummary(NewsSummaryScope.FULL, FULL_TEXT);

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(15, 29)));
		InstrumentNewsResponse beforeClose = instrumentNewsQueryService.getInstrumentNews(stock.getId());

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(15, 31)));
		InstrumentNewsResponse afterClose = instrumentNewsQueryService.getInstrumentNews(stock.getId());

		assertThat(beforeClose.summaryScope()).isEqualTo(NewsSummaryScope.PRE_MARKET);
		assertThat(beforeClose.summary()).isEqualTo(PRE_MARKET_TEXT);
		assertThat(afterClose.summaryScope()).isEqualTo(NewsSummaryScope.FULL);
		assertThat(afterClose.summary()).isEqualTo(FULL_TEXT);
		assertThat(beforeClose.originTradeDate()).isEqualTo(afterClose.originTradeDate());

		assertThat(redisTemplate.opsForValue().get(stockSummaryKey(NewsSummaryScope.PRE_MARKET)))
			.isEqualTo(PRE_MARKET_TEXT);
		assertThat(redisTemplate.opsForValue().get(stockSummaryKey(NewsSummaryScope.FULL)))
			.isEqualTo(FULL_TEXT);
	}

	@Test
	@DisplayName("코인 요약 키의 실제 TTL이 다음 정시 05분을 넘지 않는다 — 10:03이면 120초 이하, 10:07이면 3480초 이하")
	void cryptoSummaryKeyNeverOutlivesTheNextHourlyBatchMark() {
		saveNews(crypto, "코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			crypto, SERVICE_DATE, NewsSummaryScope.ROLLING_24H, "코인 요약입니다.", NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 5))));

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 3)));
		instrumentNewsQueryService.getInstrumentNews(crypto.getId());
		Long justBeforeTheMark = redisTemplate.getExpire(cryptoSummaryKey(), TimeUnit.SECONDS);

		FeedbackQueryCacheTestKeys.clear(redisTemplate);
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 7)));
		instrumentNewsQueryService.getInstrumentNews(crypto.getId());
		Long justAfterTheMark = redisTemplate.getExpire(cryptoSummaryKey(), TimeUnit.SECONDS);

		assertThat(justBeforeTheMark).isPositive().isLessThanOrEqualTo(120L);
		assertThat(justAfterTheMark).isPositive().isLessThanOrEqualTo(3480L);
	}

	@Test
	@DisplayName("Redis에 닿지 못해도 요약 조회·브리핑 조회가 예외 없이 정상 응답 본문을 낸다")
	void bothQueriesStayNormalWhenRedisIsUnreachable() throws IOException {
		saveNews(stock, "전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockSummary(NewsSummaryScope.PRE_MARKET, PRE_MARKET_TEXT);
		marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, "간밤 기사가 이어졌습니다.", NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));

		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration("127.0.0.1", closedPort()));
		deadFactory.afterPropertiesSet();
		deadFactory.start();
		try {
			StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
			FeedbackQueryCache cacheOnDeadRedis = new FeedbackQueryCache(
				deadTemplate, new RedisLock(deadTemplate), objectMapper, clock, cacheProperties, newsProperties);
			InstrumentNewsQueryService newsOnDeadRedis = new InstrumentNewsQueryService(
				new InstrumentNewsQueryReader(instrumentService, marketNewsItemRepository,
					instrumentNewsSummaryRepository, businessDayCalendar, newsProperties),
				stockReplayService, cacheOnDeadRedis, clock);
			MarketBriefingService briefingOnDeadRedis = new MarketBriefingService(
				marketNewsItemRepository, marketBriefingRepository, stockReplayService, narrativeService,
				new MarketBriefingReader(marketNewsItemRepository, marketBriefingRepository, businessDayCalendar,
					newsProperties),
				cacheOnDeadRedis, newsProperties, clock);

			assertThatCode(() -> newsOnDeadRedis.getInstrumentNews(stock.getId())).doesNotThrowAnyException();
			assertThatCode(() -> briefingOnDeadRedis.getBriefing(Market.STOCK)).doesNotThrowAnyException();

			InstrumentNewsResponse newsWithoutRedis = newsOnDeadRedis.getInstrumentNews(stock.getId());
			MarketBriefingResponse briefingWithoutRedis = briefingOnDeadRedis.getBriefing(Market.STOCK);

			assertThat(newsWithoutRedis.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(newsWithoutRedis.summary()).isEqualTo(PRE_MARKET_TEXT);
			assertThat(newsWithoutRedis.items()).isNotEmpty();
			assertThat(briefingWithoutRedis.status()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(briefingWithoutRedis.summary()).isEqualTo("간밤 기사가 이어졌습니다.");
			assertThat(briefingWithoutRedis.items()).isNotEmpty();

			assertThat(newsWithoutRedis).isEqualTo(instrumentNewsQueryService.getInstrumentNews(stock.getId()));
			assertThat(briefingWithoutRedis).isEqualTo(marketBriefingService.getBriefing(Market.STOCK));
		} finally {
			deadFactory.destroy();
		}
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	@DisplayName("요약 조회·브리핑 조회 전후로 주문·체결·계좌·잔액·보유·손익이 변하지 않는다")
	void neitherQueryEverTouchesTheLedger() {
		saveNews(stock, "전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveNews(crypto, "코인 기사", LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));
		saveStockSummary(NewsSummaryScope.PRE_MARKET, PRE_MARKET_TEXT);
		marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, "간밤 기사가 이어졌습니다.", NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
		Map<String, Object> before = ledgerSnapshot();

		InstrumentNewsResponse news = instrumentNewsQueryService.getInstrumentNews(stock.getId());
		MarketBriefingResponse briefing = marketBriefingService.getBriefing(Market.STOCK);
		instrumentNewsQueryService.getInstrumentNews(crypto.getId());
		marketBriefingService.getBriefing(Market.CRYPTO);

		assertThat(news.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(briefing.status()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(redisTemplate.keys(FeedbackQueryCacheTestKeys.PATTERN)).isNotEmpty();
		assertThat(ledgerSnapshot()).isEqualTo(before);
	}

	private Map<String, Object> ledgerSnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		for (String table : LEDGER_TABLES) {
			snapshot.put(table + ".count", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		snapshot.put("accounts.cash_balance", jdbcTemplate.queryForObject(
			"SELECT COALESCE(SUM(cash_balance), 0) FROM accounts", Long.class));
		snapshot.put("accounts.realized_pnl", jdbcTemplate.queryForObject(
			"SELECT COALESCE(SUM(realized_pnl), 0) FROM accounts", Long.class));
		snapshot.put("holdings.quantity", jdbcTemplate.queryForObject(
			"SELECT COALESCE(SUM(quantity), 0) FROM holdings", BigDecimal.class));
		snapshot.put("trades.realized_pnl", jdbcTemplate.queryForObject(
			"SELECT COALESCE(SUM(realized_pnl), 0) FROM trades", Long.class));
		return snapshot;
	}

}
