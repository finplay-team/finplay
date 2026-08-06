// 조회 캐시의 경계 조건 — 주식 범위 전환·코인 매시 주기 TTL·Redis 장애·원장 불변을 실 MySQL/Redis로 검증한다 (tasks.md 항목 7).
package com.finplay.api.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.service.InstrumentNewsQueryReader;
import com.finplay.api.feedback.service.InstrumentNewsQueryService;
import com.finplay.api.feedback.service.MarketBriefingReader;
import com.finplay.api.feedback.service.MarketBriefingService;
import com.finplay.api.feedback.service.NarrativeService;
import com.finplay.api.feedback.service.RedisLock;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * tasks.md 항목 7의 네 경계다. 앞 항목들이 "캐시가 효과가 있는가"를 봤다면 여기는 <b>캐시가 틀리지 않는가</b>를
 * 본다 — 넷 다 어긋나도 예외나 로그가 남지 않고 값만 조용히 달라지는 자리다.
 *
 * <pre>
 * 범위 전환   15:30을 넘기면 다른 요약을 봐야 한다 — 안 갈리면 FULL 문장이 장중에 새거나 그 반대다
 * 코인 주기   TTL이 다음 정시 05분을 넘으면 배치가 갱신한 뒤에도 옛 서술이 남는다
 * Redis 장애  캐시가 없으면 느려질 뿐이어야 하고, 조회가 실패하면 안 된다
 * 원장 불변   GET이 원장에 쓰면 그건 조회가 아니다
 * </pre>
 */
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, FeedbackQueryCacheBoundaryIntegrationTest.MutableClockTestConfig.class})
@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheBoundaryIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";

	private static final String PRE_MARKET_TEXT = "개장 전까지의 기사만 다룬 요약입니다.";

	private static final String FULL_TEXT = "장 마감까지 하루 전체를 다룬 요약입니다.";

	// 조회 전후로 행도 값도 변하면 안 되는 원장 테이블 (주문·체결·계좌/잔액·보유·손익 배분).
	// 목록은 NewsCollectionIntegrationTest·CryptoPriceMoveWatcherIntegrationTest와 같은 것을 쓴다.
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
	private Clock clock;

	private MutableClock mutableClock;

	private Instrument stock;

	private Instrument crypto;

	@BeforeEach
	void setUp() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
		mutableClock = (MutableClock)clock;
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

	// ── 픽스처 ───────────────────────────────────────────────────────────────────────

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

	// ── ① 주식 범위 전환 ─────────────────────────────────────────────────────────────

	/*
	 * 정확성의 근거는 TTL이 아니라 **키에 scope가 들어간 것**이다(ADR-0015 §1). 15:30을 넘기면 조회가 FULL 키를
	 * 보므로 PRE_MARKET 값이 Redis에 남아 있어도 읽히지 않는다 — 남은 값을 지우는 일 없이 자동으로 갈린다.
	 * 이것이 깨지면 FULL 한 문장이 장중에 새어 그날 오후를 통째로 알려준다(FEED-008).
	 */
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

		// 두 값이 서로 다른 키에 따로 담겼다 — 갈림이 우연이 아니라 키 구조 때문임을 못박는다.
		assertThat(redisTemplate.opsForValue().get(stockSummaryKey(NewsSummaryScope.PRE_MARKET)))
			.isEqualTo(PRE_MARKET_TEXT);
		assertThat(redisTemplate.opsForValue().get(stockSummaryKey(NewsSummaryScope.FULL)))
			.isEqualTo(FULL_TEXT);
	}

	// ── ② 코인 매시 주기 TTL ─────────────────────────────────────────────────────────

	/*
	 * 저장할 때 넘긴 Duration이 아니라 Redis에 실제로 걸린 TTL을 본다 — 계산이 맞아도 저장 경로에서 다른 값이
	 * 들어가면 배치가 갱신한 뒤에도 옛 서술이 다음 주기까지 남는다. 상한만 단정하는 것이 요지다("넘지 않는다").
	 */
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

		// 10:03 → 10:05까지 120초.
		assertThat(justBeforeTheMark).isPositive().isLessThanOrEqualTo(120L);
		// 10:07 → 05분을 이미 지났으므로 이번 시각이 아니라 11:05까지 3480초. 여기서 다음 시각으로 넘기지
		// 못하면 TTL이 음수가 되어 저장 자체가 건너뛰어진다.
		assertThat(justAfterTheMark).isPositive().isLessThanOrEqualTo(3480L);
	}

	// ── ③ Redis 장애 ────────────────────────────────────────────────────────────────

	/*
	 * 실제로 닿지 못하는 Redis를 만든다 — 방금 닫은 포트를 향하는 LettuceConnectionFactory다. mock으로 예외를
	 * 흉내 내면 "우리가 예상한 예외 타입"만 보게 되는데, 실제 드라이버가 던지는 것은 그것과 다를 수 있고 그
	 * 차이가 정확히 이 테스트가 막으려는 사고다.
	 *
	 * 단정을 "예외가 안 난다"로 끝내지 않고 **정상 Redis에서의 응답과 통째로 같다**로 둔다 — 예외만 안 나고
	 * 상태값이 UNAVAILABLE로 떨어지거나 items가 비면 사용자에게는 그것도 장애다.
	 */
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
			// DB 읽기는 Reader가 자기 트랜잭션에서 끝낸다(PR #257) — 여기서는 캐시만 죽은 Redis로 바꿔 끼우고
			// Reader는 스프링 빈과 같은 협력자로 조립한다.
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

			// 본문이 실제로 채워져 있다 — 예외만 안 나고 빈 응답이면 그것도 장애다.
			assertThat(newsWithoutRedis.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(newsWithoutRedis.summary()).isEqualTo(PRE_MARKET_TEXT);
			assertThat(newsWithoutRedis.items()).isNotEmpty();
			assertThat(briefingWithoutRedis.status()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(briefingWithoutRedis.summary()).isEqualTo("간밤 기사가 이어졌습니다.");
			assertThat(briefingWithoutRedis.items()).isNotEmpty();

			// 정상 Redis에서의 응답과 통째로 같다 — 캐시 장애가 "미스"일 뿐 계약을 바꾸지 않는다.
			assertThat(newsWithoutRedis).isEqualTo(instrumentNewsQueryService.getInstrumentNews(stock.getId()));
			assertThat(briefingWithoutRedis).isEqualTo(marketBriefingService.getBriefing(Market.STOCK));
		} finally {
			deadFactory.destroy();
		}
	}

	// 방금 닫은 포트라 확실히 비어 있다 — 리터럴 포트를 박으면 다른 프로세스가 그 포트를 쓰고 있을 때
	// "Redis가 죽어 있다"는 전제가 조용히 깨진다.
	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	// ── ④ 원장 불변 ─────────────────────────────────────────────────────────────────

	/*
	 * 행 수만 세면 UPDATE(잔액·손익 변동)를 놓치므로 금액 컬럼의 합도 함께 찍는다. 그리고 조회가 실제로
	 * 일을 했는지를 먼저 확인한다 — 아무 일도 안 한 조회 뒤에 원장이 그대로인 것은 아무 의미가 없다.
	 */
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

		// 조회가 실제로 값을 냈고 캐시에도 썼다 — 그런데도 원장이 그대로여야 의미가 있다.
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
		// 행 수가 같아도 값이 바뀌었을 수 있다 — 잔액·손익·보유 수량은 UPDATE로 움직이는 컬럼이다.
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

	@TestConfiguration
	static class MutableClockTestConfig {

		@Bean
		@Primary
		Clock mutableClock() {
			return new MutableClock(INITIAL_NOW.atZone(KST).toInstant());
		}
	}

	// 같은 픽스처를 여러 시각에서 조회해야 범위 전환의 양쪽을 볼 수 있다 (게이트 테스트들과 같은 방식).
	private static final class MutableClock extends Clock {

		private volatile Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(KST).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return KST;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
