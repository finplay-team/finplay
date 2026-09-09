package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.CryptoPriceSnapshotService;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoWatchLockConcurrencyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String INSTRUMENT_NAME = "락경합코인";
	private static final String WATCH_LOCK_KEY_PREFIX = "feedback:crypto-watch:lock:";

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	@Autowired
	private CryptoWatchLock cryptoWatchLock;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private CryptoPriceSnapshotService cryptoPriceSnapshotService;

	@Autowired
	private PriceMoveCardWriter priceMoveCardWriter;

	@Autowired
	private NewsMatcher newsMatcher;

	@Autowired
	private NewsCollectionService newsCollectionService;

	@Autowired
	private FeedbackCryptoProperties cryptoProperties;

	@Autowired
	private FeedbackDetectionProperties detectionProperties;

	@Autowired
	private TestClock clock;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private NarrativeService narrativeService;

	private String symbol;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		symbol = "LOCKR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, INSTRUMENT_NAME, BigDecimal.ONE, 5000L, true, NOW));
		redisTemplate.delete(watchLockKey());
		when(narrativeService.resolvePriceMoveNarrative(any())).thenReturn(NarrativeResultDto.template("변동 설명"));
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update(
			"DELETE FROM price_move_event_sources WHERE price_move_event_id IN "
				+ "(SELECT id FROM price_move_events WHERE instrument_id = ?)",
			instrument.getId());
		jdbcTemplate.update("DELETE FROM price_move_events WHERE instrument_id = ?", instrument.getId());
		jdbcTemplate.update("DELETE FROM market_news_items WHERE instrument_id = ?", instrument.getId());
		jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrument.getId());
		redisTemplate.delete("price:crypto:" + symbol + ":snapshots");
		redisTemplate.delete(watchLockKey());
	}

	private String watchLockKey() {
		return WATCH_LOCK_KEY_PREFIX + instrument.getId();
	}

	private void givenEnoughSnapshotsWithARecentJump() {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(symbol, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(symbol, NOW, now, retention);
	}

	private void givenMatchingNews() {
		marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "락 경합 테스트 기사", "테스트경제",
			"https://news.example.com/lock-race", NOW.minusMinutes(5), NOW));
	}

	private static boolean isThisTestsInstrument(PriceMovePromptDto prompt) {
		return prompt.instrumentName().equals(INSTRUMENT_NAME);
	}

	@Test
	@DisplayName("[방어 켠 상태] 실제 Redis 락으로 두 스레드가 동시에 watch()를 실행해도 카드는 1건만 저장되고 NarrativeService도 1회만 불린다")
	void watchWithRealLockPersistsExactlyOneCardAndCallsNarrativeServiceOnce() throws Exception {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();

		runConcurrently(cryptoPriceMoveWatcher::watch, cryptoPriceMoveWatcher::watch);

		long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
		assertThat(cardCount).isEqualTo(1L);
		verify(narrativeService, times(1))
			.resolvePriceMoveNarrative(argThat(CryptoWatchLockConcurrencyIntegrationTest::isThisTestsInstrument));
	}

	@Test
	@DisplayName("[방어 비활성/우회 재현] 락이 실제 상호 배제를 하지 않으면(모두 획득에 성공한다고 착각) "
		+ "두 스레드 동시 실행이 카드 2건·NarrativeService 2회 호출로 재현된다")
	void watchWithoutRealMutualExclusionReproducesDuplicateCardsAndNarrativeCalls() throws Exception {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();
		CyclicBarrier bothReachedNarrativeCall = new CyclicBarrier(2);
		when(narrativeService.resolvePriceMoveNarrative(any())).thenAnswer(invocation -> {
			bothReachedNarrativeCall.await(10, TimeUnit.SECONDS);
			return NarrativeResultDto.template("변동 설명");
		});
		CryptoPriceMoveWatcher watcherWithoutRealLock = new CryptoPriceMoveWatcher(
			instrumentService, cryptoPriceSnapshotService, priceMoveEventRepository, priceMoveCardWriter,
			alwaysSucceedingLockWithFreshTokens(), newsMatcher, newsCollectionService,
			narrativeService, cryptoProperties, detectionProperties, clock);

		runConcurrently(watcherWithoutRealLock::watch, watcherWithoutRealLock::watch);

		long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
		assertThat(cardCount).isEqualTo(2L);
		verify(narrativeService, times(2))
			.resolvePriceMoveNarrative(argThat(CryptoWatchLockConcurrencyIntegrationTest::isThisTestsInstrument));
	}

	@Test
	@DisplayName("[결정론적 방어 확인] 테스트 스레드가 실제 락을 먼저 쥔 상태면 watch()가 카드도, "
		+ "NarrativeService 호출도 만들지 않는다")
	void watchSkipsEntirelyWhenTheRealLockIsAlreadyHeld() {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();
		Optional<String> heldToken = cryptoWatchLock.tryLock(instrument.getId());
		assertThat(heldToken).isPresent();

		try {
			cryptoPriceMoveWatcher.watch();

			long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
				instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
			assertThat(cardCount).isEqualTo(0L);
			verify(narrativeService, never())
				.resolvePriceMoveNarrative(argThat(CryptoWatchLockConcurrencyIntegrationTest::isThisTestsInstrument));
		} finally {
			cryptoWatchLock.unlock(instrument.getId(), heldToken.get());
		}
	}

	private static CryptoWatchLock alwaysSucceedingLockWithFreshTokens() {
		CryptoWatchLock lock = mock(CryptoWatchLock.class);
		when(lock.tryLock(any())).thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
		return lock;
	}

	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			start.await();
			action.run();
			return null;
		};
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

}
