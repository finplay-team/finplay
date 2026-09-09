package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
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
import com.finplay.api.domain.feedback.service.InstrumentNewsQueryService;
import com.finplay.api.domain.feedback.service.MarketBriefingPromptDto;
import com.finplay.api.domain.feedback.service.MarketBriefingService;
import com.finplay.api.domain.feedback.service.NarrativeResultDto;
import com.finplay.api.domain.feedback.service.NarrativeService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.lock.RedisLock;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import({TestcontainersConfiguration.class,
	FeedbackQueryCacheConnectionHoldingIntegrationTest.ConnectionHoldingTestConfig.class})
@TestPropertySource(properties = {
	"feedback.query-cache.enabled=true",
	"feedback.query-cache.wait-millis=6000",
	"feedback.query-cache.poll-millis=50",
	"spring.datasource.hikari.maximum-pool-size=4",
	"spring.datasource.hikari.connection-timeout=1000"})
class FeedbackQueryCacheConnectionHoldingIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NOW = LocalDateTime.of(2033, 8, 6, 10, 0);

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2033, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2033, 8, 4);

	private static final String STOCK_SYMBOL = "005930";

	private static final String URL_PREFIX = "https://news.example.test/pool/";

	private static final int CONCURRENT_QUERIES = 4;

	private static final long SAMPLE_AFTER_MILLIS = 1500;

	private static final String SUMMARY_TEXT = "최근 24시간 기사가 이어졌습니다.";

	@Autowired
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	private TransactionalQueryWrapper transactionalQueryWrapper;

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private MarketBriefingService marketBriefingService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@MockitoBean
	private NarrativeService narrativeService;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Instrument crypto;

	private Instrument stock;

	@BeforeEach
	void setUp() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("간밤 기사가 이어졌습니다."));

		crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO,
			"POOL" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
			"커넥션코인", BigDecimal.ONE, 5000L, true, NOW));
		stock = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();

		marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			crypto, MarketNewsItemType.NEWS, "코인 기사", "테스트경제",
			URL_PREFIX + crypto.getSymbol(), NOW.minusHours(1), NOW));
		instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			crypto, NOW.toLocalDate(), NewsSummaryScope.ROLLING_24H, SUMMARY_TEXT, NarrativeSource.LLM,
			LocalDateTime.of(NOW.toLocalDate(), LocalTime.of(9, 5))));
		marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.CRYPTO, NOW.toLocalDate(), "최근 24시간 코인 기사가 이어졌습니다.", NarrativeSource.LLM, NOW));

		marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			stock, MarketNewsItemType.NEWS, "전일 저녁 기사", "테스트경제",
			URL_PREFIX + "stock", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)), NOW));
		stockReplaySessionRepository.saveAndFlush(StockReplaySession.ready(
			NOW.toLocalDate(),
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(NOW.toLocalDate(), LocalTime.of(8, 40)),
			LocalDateTime.of(NOW.toLocalDate(), LocalTime.of(8, 0))));
	}

	@AfterEach
	void cleanUp() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
		jdbcTemplate.update("DELETE FROM market_briefings WHERE origin_trade_date BETWEEN ? AND ?",
			LocalDate.of(2033, 1, 1), LocalDate.of(2033, 12, 31));
		jdbcTemplate.update("DELETE FROM instrument_news_summaries WHERE instrument_id = ?", crypto.getId());
		jdbcTemplate.update("DELETE FROM market_news_items WHERE url LIKE ?", URL_PREFIX + "%");
		jdbcTemplate.update("DELETE FROM stock_replay_sessions WHERE service_date = ?", NOW.toLocalDate());
		jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", crypto.getId());
	}

	private String summaryLockKey() {
		return "feedback:query-cache:lock:v1:crypto-summary:" + crypto.getId();
	}

	private int activeConnections() throws Exception {
		return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
	}

	@Test
	@DisplayName("[트랜잭션 밖] 앰비언트 트랜잭션 없이 조회해도 지연 로딩 예외 없이 완전한 응답이 나온다")
	void queriesCompleteOutsideAnyAmbientTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("이 테스트의 전제 — 운영과 같이 바깥 트랜잭션이 없다")
			.isFalse();

		InstrumentNewsResponse cryptoResponse = instrumentNewsQueryService.getInstrumentNews(crypto.getId());

		assertThat(cryptoResponse.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(cryptoResponse.summary()).isEqualTo(SUMMARY_TEXT);
		assertThat(cryptoResponse.items())
			.isNotEmpty()
			.allSatisfy(item -> {
				assertThat(item.title()).isNotBlank();
				assertThat(item.publisher()).isNotBlank();
				assertThat(item.publishedAt()).isNotNull();
			});
	}

	@Test
	@DisplayName("[트랜잭션 밖] 주식·코인 브리핑 조회가 items의 종목명·심볼까지 지연 로딩 예외 없이 낸다")
	void briefingQueriesResolveInstrumentIdentityOutsideAnyAmbientTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, "간밤 기사가 이어졌습니다.", NarrativeSource.LLM, NOW));

		MarketBriefingResponse stockBriefing = marketBriefingService.getBriefing(Market.STOCK);
		MarketBriefingResponse cryptoBriefing = marketBriefingService.getBriefing(Market.CRYPTO);

		assertThat(stockBriefing.items())
			.isNotEmpty()
			.allSatisfy(item -> {
				assertThat(item.symbol()).isEqualTo(STOCK_SYMBOL);
				assertThat(item.name()).isNotBlank();
				assertThat(item.instrumentId()).isNotNull();
			});
		assertThat(cryptoBriefing.items())
			.isNotEmpty()
			.allSatisfy(item -> {
				assertThat(item.symbol()).isEqualTo(crypto.getSymbol());
				assertThat(item.name()).isEqualTo("커넥션코인");
			});
	}

	@Test
	@DisplayName("[트랜잭션 밖] 주식 브리핑 생성이 프롬프트에 종목명을 붙이는 동안 지연 로딩 예외가 나지 않는다")
	void stockBriefingGenerationResolvesInstrumentNamesOutsideAnyAmbientTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

		assertThatCode(() -> marketBriefingService.generateStockBriefing(ORIGIN_TRADE_DATE))
			.doesNotThrowAnyException();

		ArgumentCaptor<MarketBriefingPromptDto> prompt = ArgumentCaptor.forClass(MarketBriefingPromptDto.class);
		verify(narrativeService).resolveMarketBriefingNarrative(prompt.capture());
		assertThat(prompt.getValue().items())
			.isNotEmpty()
			.allSatisfy(item -> assertThat(item.instrumentName()).isNotBlank());
	}

	@Test
	@DisplayName("[방어군] 캐시 대기 중인 조회 4건이 커넥션을 0개 쥐고, 그 사이 들어온 다섯 번째 조회도 정상이다")
	void waitingQueriesHoldNoConnectionSoAnotherQueryStillGetsThrough() throws Exception {
		Observation observation = observeWhileQueriesWait(instrumentNewsQueryService::getInstrumentNews);

		assertThat(observation.activeConnectionsDuringWait())
			.as("대기 스레드가 커넥션을 쥐지 않는 것이 이 수정의 전부다")
			.isZero();
		assertThatCode(observation.extraQuery()).doesNotThrowAnyException();
		assertThat(observation.responses())
			.hasSize(CONCURRENT_QUERIES)
			.allSatisfy(response -> {
				assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
				assertThat(response.summary()).isEqualTo(SUMMARY_TEXT);
			});
	}

	@Test
	@DisplayName("[대조군] 조회를 통째로 @Transactional로 감싸면 대기 4건이 풀을 채우고 다섯 번째 조회가 커넥션을 못 얻는다")
	void wrappingTheWholeQueryInATransactionExhaustsThePoolWhileWaiting() throws Exception {
		Observation observation = observeWhileQueriesWait(transactionalQueryWrapper::getInstrumentNews);

		assertThat(observation.activeConnectionsDuringWait())
			.as("대기 중인데도 커넥션을 쥐고 있다 — 이것이 고치기 전 상태다")
			.isEqualTo(CONCURRENT_QUERIES);
		assertThatThrownBy(observation.extraQuery())
			.as("풀이 대기 스레드로 가득 차 새 요청이 커넥션을 얻지 못한다")
			.hasStackTraceContaining("Connection is not available");
		assertThat(observation.responses()).hasSize(CONCURRENT_QUERIES);
	}

	private Observation observeWhileQueriesWait(LongFunction<InstrumentNewsResponse> query) throws Exception {
		String heldToken = redisLock.tryLock(summaryLockKey(), Duration.ofSeconds(30)).orElseThrow();
		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_QUERIES);
		try {
			CyclicBarrier atTheGate = new CyclicBarrier(CONCURRENT_QUERIES + 1);
			List<Future<InstrumentNewsResponse>> futures = IntStream.range(0, CONCURRENT_QUERIES)
				.mapToObj(index -> executor.<InstrumentNewsResponse>submit(() -> {
					atTheGate.await(20, TimeUnit.SECONDS);
					return query.apply(crypto.getId());
				}))
				.toList();

			atTheGate.await(20, TimeUnit.SECONDS);
			Thread.sleep(SAMPLE_AFTER_MILLIS);
			int activeDuringWait = activeConnections();
			Throwable extraQueryFailure = captureFailure(() -> query.apply(crypto.getId()));

			List<InstrumentNewsResponse> responses = futures.stream()
				.map(future -> {
					try {
						return future.get(30, TimeUnit.SECONDS);
					} catch (Exception ex) {
						throw new IllegalStateException("동시 조회가 끝나지 않았다", ex);
					}
				})
				.toList();
			return new Observation(activeDuringWait, extraQueryFailure, responses);
		} finally {
			redisLock.unlock(summaryLockKey(), heldToken);
			executor.shutdownNow();
		}
	}

	private static Throwable captureFailure(Runnable action) {
		try {
			action.run();
			return null;
		} catch (Throwable ex) {
			return ex;
		}
	}

	private record Observation(
		int activeConnectionsDuringWait, Throwable extraQueryFailure, List<InstrumentNewsResponse> responses) {

		org.assertj.core.api.ThrowableAssert.ThrowingCallable extraQuery() {
			return () -> {
				if (extraQueryFailure != null) {
					throw extraQueryFailure;
				}
			};
		}
	}

	@TestConfiguration
	static class ConnectionHoldingTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}

		@Bean
		TransactionalQueryWrapper transactionalQueryWrapper(InstrumentNewsQueryService instrumentNewsQueryService) {
			return new TransactionalQueryWrapper(instrumentNewsQueryService);
		}
	}

	static class TransactionalQueryWrapper {

		private final InstrumentNewsQueryService instrumentNewsQueryService;

		TransactionalQueryWrapper(InstrumentNewsQueryService instrumentNewsQueryService) {
			this.instrumentNewsQueryService = instrumentNewsQueryService;
		}

		@Transactional(readOnly = true)
		public InstrumentNewsResponse getInstrumentNews(Long instrumentId) {
			return instrumentNewsQueryService.getInstrumentNews(instrumentId);
		}
	}
}
