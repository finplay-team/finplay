// 캐시 락 대기 동안 JDBC 커넥션을 쥐는지 대조한다 — 조회를 통째로 @Transactional로 감싼 테스트 전용 래퍼(대조군)와 지금 구조(방어군)를 같은 조건에서 비교한다 (PR #257 남은 위험 1).
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

/**
 * PR #257 "남은 위험 1"에 대한 대조다. 위험은 이것이었다 — 조회 메서드를 통째로
 * {@code @Transactional(readOnly = true)}로 두면 {@code FeedbackQueryCache}의 폴링 대기(최대
 * {@code wait-millis}) 동안 JDBC 커넥션을 쥔 채로 있게 되고, <b>만료 경계에 요청이 몰리는 바로 그 순간</b>
 * 대기 스레드가 커넥션 풀을 채워 뒤따르는 요청이 커넥션 획득에서 막힌다. 캐시가 막으려던 것보다 나쁜 실패다.
 *
 * <p><b>"구조를 바꿨다"로는 증명이 되지 않는다.</b> 방어군만 초록이면 고쳐서 통과한 것인지 원래 통과했을
 * 것인지 구분되지 않는다. 그래서 <b>조회를 통째로 감싸는 테스트 전용 래퍼 빈</b>({@link TransactionalQueryWrapper})
 * 을 두고 같은 조건에서 나란히 돌린다 — 옛 구조가 그것과 같은 모양이었다.
 *
 * <p><b>운영 코드에 대조용 스위치를 넣지 않는다.</b> 래퍼는 이 테스트의 {@code @TestConfiguration}에만 있고,
 * 안에서 실제 서비스를 그대로 호출한다. 두 갈래의 차이는 <b>바깥 트랜잭션의 유무 하나</b>뿐이다.
 *
 * <p><b>관측은 두 가지다.</b> 대기 도중의 활성 커넥션 수를 직접 재고(주장 그 자체), 그 상태에서 요청 1건이
 * 더 들어올 수 있는지를 본다(사용자가 겪는 형태). 앞의 것만 재면 "그래서 무슨 일이 나는가"가 빠지고, 뒤의
 * 것만 보면 실패 원인이 커넥션인지 다른 것인지 흐려진다.
 *
 * <p><b>{@code @Transactional}을 쓰지 않는다</b> — 여러 스레드가 각자 커넥션을 다퉈야 하고, 테스트 자신이
 * 트랜잭션을 열면 그 커넥션까지 풀에서 빠져 대조가 흐려진다. 픽스처는 커밋되므로 {@code @AfterEach}에서
 * 직접 지운다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class,
	FeedbackQueryCacheConnectionHoldingIntegrationTest.ConnectionHoldingTestConfig.class})
@TestPropertySource(properties = {
	"feedback.query-cache.enabled=true",
	// 대기가 실제로 "도는" 동안 관측해야 하므로 넉넉히 올린다. 기본 300ms면 표본을 뜨기도 전에 끝난다.
	"feedback.query-cache.wait-millis=6000",
	"feedback.query-cache.poll-millis=50",
	// 동시 조회 수와 같게 잡는다 — 대조군에서 네 스레드가 전부 쥐면 다섯 번째 요청이 들어올 자리가 없다.
	"spring.datasource.hikari.maximum-pool-size=4",
	// 고갈이 무한 대기가 아니라 빠른 실패로 드러나게 한다.
	"spring.datasource.hikari.connection-timeout=1000"})
class FeedbackQueryCacheConnectionHoldingIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 커밋되는 픽스처라 저장소 안에서 이 클래스만 쓰는 연도를 쓴다 (ai/agent-mistakes.md의 UNIQUE 충돌 행).
	private static final LocalDateTime NOW = LocalDateTime.of(2033, 8, 6, 10, 0);

	// 서비스 날짜 2033-08-06(토) 기준 원본 거래일과 그 직전 영업일. 브리핑 전장 구간이 [D-1 15:30, D 09:00]이다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2033, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2033, 8, 4);

	private static final String STOCK_SYMBOL = "005930";

	// 이 클래스가 만든 기사만 골라 지우려고 붙이는 표식 — 픽스처가 커밋되므로 정리가 정확해야 한다.
	private static final String URL_PREFIX = "https://news.example.test/pool/";

	private static final int CONCURRENT_QUERIES = 4;

	// wait-millis(6000) 한가운데다 — 이 시점이면 네 스레드가 전부 폴링 대기에 들어가 있다.
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

	// 실 LLM을 부르지 않으면서 생성 프롬프트를 잡아 보려고 대체한다.
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

		// 브리핑 전장 구간 [2033-08-04 15:30, 2033-08-05 09:00] 안에 드는 주식 기사.
		marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			stock, MarketNewsItemType.NEWS, "전일 저녁 기사", "테스트경제",
			URL_PREFIX + "stock", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)), NOW));
		stockReplaySessionRepository.saveAndFlush(StockReplaySession.ready(
			NOW.toLocalDate(),
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(NOW.toLocalDate(), LocalTime.of(8, 40)),
			LocalDateTime.of(NOW.toLocalDate(), LocalTime.of(8, 0))));
	}

	// 이 클래스는 @Transactional이 아니라 픽스처가 커밋된다 — 남기면 다른 테스트의 시장 단위 질의에 섞인다.
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

	// --- 엔티티가 트랜잭션 밖으로 새지 않는다 ---

	/*
	 * 조회 메서드에서 @Transactional이 사라졌으므로, 매핑이 Reader 안에서 끝나지 않으면 호출부에서 지연 로딩
	 * 예외가 난다. **기존 통합 테스트로는 이것을 잡을 수 없다** — 그쪽은 클래스에 @Transactional이 걸려 있어
	 * 테스트 자신의 트랜잭션이 열려 있고, Reader들이 거기 합류해 엔티티가 계속 관리 상태로 남기 때문이다.
	 * 이 클래스만 앰비언트 트랜잭션이 없어 운영과 같은 조건이다.
	 */
	@Test
	@DisplayName("[트랜잭션 밖] 앰비언트 트랜잭션 없이 조회해도 지연 로딩 예외 없이 완전한 응답이 나온다")
	void queriesCompleteOutsideAnyAmbientTransaction() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("이 테스트의 전제 — 운영과 같이 바깥 트랜잭션이 없다")
			.isFalse();

		InstrumentNewsResponse cryptoResponse = instrumentNewsQueryService.getInstrumentNews(crypto.getId());

		assertThat(cryptoResponse.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(cryptoResponse.summary()).isEqualTo(SUMMARY_TEXT);
		// 항목 필드를 실제로 만져야 지연 로딩이 드러난다 — 목록 크기만 보면 프록시를 건드리지 않고 지나간다.
		assertThat(cryptoResponse.items())
			.isNotEmpty()
			.allSatisfy(item -> {
				assertThat(item.title()).isNotBlank();
				assertThat(item.publisher()).isNotBlank();
				assertThat(item.publishedAt()).isNotNull();
			});
	}

	/*
	 * 위 종목 뉴스 조회만으로는 부족하다 — 그 매핑(NewsItem.from)은 지연 연관을 하나도 만지지 않아
	 * JOIN FETCH를 지워도 통과한다. 트랜잭션 밖에서 실제로 instrument를 역참조하는 자리는
	 * **BriefingNewsItem.from(브리핑 items)와 MarketBriefingService.toPromptItem(생성 프롬프트)** 둘이고,
	 * 둘 다 종목명·심볼을 읽는다. 그래서 여기서 name·symbol을 직접 단정한다.
	 *
	 * MarketBriefingReader의 두 질의가 JOIN FETCH라 트랜잭션이 닫힌 뒤에도 초기화돼 있다는 주석을 코드로
	 * 고정하는 것이 목적이다 — JOIN FETCH를 지우면 LazyInitializationException으로 red가 된다(확인했다).
	 */
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

	// 생성 경로는 조회와 매핑이 다르다 — toPromptItem이 기사마다 종목명을 붙인다(시장 단위 단일 목록이라
	// 어느 종목 소식인지 모델이 알 수 없기 때문이다). Reader가 엔티티를 그대로 돌려주고 그 역참조가
	// 트랜잭션 밖에서 일어나므로, 조회와 별개로 여기도 JOIN FETCH에 기대고 있다.
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

	// --- 방어군: 지금 구조 (조회 메서드에 트랜잭션이 없다) ---

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

	// --- 대조군: 조회를 통째로 감싼 트랜잭션 (옛 구조와 같은 모양) ---

	@Test
	@DisplayName("[대조군] 조회를 통째로 @Transactional로 감싸면 대기 4건이 풀을 채우고 다섯 번째 조회가 커넥션을 못 얻는다")
	void wrappingTheWholeQueryInATransactionExhaustsThePoolWhileWaiting() throws Exception {
		Observation observation = observeWhileQueriesWait(transactionalQueryWrapper::getInstrumentNews);

		assertThat(observation.activeConnectionsDuringWait())
			.as("대기 중인데도 커넥션을 쥐고 있다 — 이것이 고치기 전 상태다")
			.isEqualTo(CONCURRENT_QUERIES);
		// 사용자가 겪는 형태다. 예외 타입까지 못박지 않는 것은 드라이버·풀 구현에 따라 감싸는 껍데기가
		// 다르기 때문이고, 여기서 확인할 것은 "요청이 실패한다"와 그 원인이 커넥션이라는 사실이다.
		assertThatThrownBy(observation.extraQuery())
			.as("풀이 대기 스레드로 가득 차 새 요청이 커넥션을 얻지 못한다")
			.hasStackTraceContaining("Connection is not available");
		// 대기하던 넷은 이미 커넥션을 쥐고 있었으므로 자기들끼리는 끝난다 — 피해를 보는 것은 뒤에 온 요청이다.
		assertThat(observation.responses()).hasSize(CONCURRENT_QUERIES);
	}

	/**
	 * 락을 테스트 스레드가 쥔 채 {@code CONCURRENT_QUERIES}건을 동시에 태우고, 그것들이 캐시 대기에 머무는
	 * 동안 활성 커넥션 수를 재고 요청 1건을 더 넣어 본다.
	 *
	 * <p>락을 미리 쥐는 것이 "대기 중"을 결정론적으로 만드는 장치다 — 스레드 경합으로 만들면 누가 락을
	 * 얻었는지에 따라 대기 스레드 수가 매번 달라져 활성 커넥션 수를 단정할 수 없다.
	 */
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
			// 표본을 뜬 직후, 대기가 아직 도는 동안에 넣는다 — 대기가 끝난 뒤면 풀이 이미 비어 의미가 없다.
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

	/** 한 갈래의 관측 결과. {@code extraQueryFailure}가 {@code null}이면 다섯 번째 조회가 성공한 것이다. */
	private record Observation(
		int activeConnectionsDuringWait, Throwable extraQueryFailure, List<InstrumentNewsResponse> responses) {

		// AssertJ에 그대로 넘기려고 실패를 다시 던지는 형태로 감싼다.
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

	/**
	 * <b>대조군 전용이다 — 운영 코드에는 이런 래퍼가 없다.</b> 실제 서비스를 그대로 부르되 바깥에
	 * {@code @Transactional(readOnly = true)}만 두른다. 안쪽 {@code Reader}들의 트랜잭션은 전파(REQUIRED)로
	 * 이 트랜잭션에 합류하므로, 커넥션이 조회가 끝날 때까지 — <b>캐시 대기를 포함해</b> — 반납되지 않는다.
	 * 나누기 전 구조가 정확히 이 모양이었다.
	 */
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
