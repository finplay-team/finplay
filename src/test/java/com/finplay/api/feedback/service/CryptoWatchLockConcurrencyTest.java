// CryptoWatchLock(ADR-0014)이 다중 인스턴스 동시 실행에서 코인 카드·NarrativeService 중복 호출을 실제로 막는지, 그리고 방어가 실제 상호 배제를 하지 않으면 중복이 재현되는지를 한 클래스에서 대조한다 (tasks-244.md 항목 4).
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.CryptoPriceSnapshotService;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// tasks-244.md 항목 4 — "방어가 없으면 실제로 중복이 생긴다"를 먼저 증명하고, 그 다음 방어가 켜진 상태에서
// 1건만 남는 것을 같은 클래스에서 대조한다. 두 테스트 모두 실제 Redis·MySQL(Testcontainers)로 같은 종목·같은
// 시각 조건을 두 스레드에서 동시에 태운다.
//
// [재현 테스트]가 쓰는 CryptoWatchLock은 Spring 빈이 아니라 이 클래스 안에서 직접 mock으로 만든 것이다 —
// tryLock을 호출마다 매번 성공(서로 다른 임의 토큰)하도록 스텁해, "락이 실제로는 아무 경합 조정도 안 하던
// 이전 상태"를 정확히 재현한다(두 스레드 다 자기가 락을 얻었다고 믿지만 서로를 막지 못한다). 이 mock을 낀
// CryptoPriceMoveWatcher를 별도 인스턴스로 새로 조립해 [방어 켠] 테스트가 쓰는 스프링 빈(실제 Redis 락)과
// 서로 영향을 주지 않게 했다.
//
// @Transactional을 쓰지 않는다 — 두 스레드가 각자 다른 커넥션에서 락을 다퉈야 하는데 테스트 메서드를
// @Transactional로 감싸면 그 안에서 만든 픽스처가 다른 스레드에는 커밋된 것으로 보이지 않을 수 있다
// (LimitOrderConcurrencyIntegrationTest와 같은 방침 — saveAndFlush로 즉시 커밋하고 @AfterEach로 직접 정리한다).
@SpringBootTest
@Import({TestcontainersConfiguration.class, CryptoWatchLockConcurrencyTest.FixedClockTestConfig.class})
class CryptoWatchLockConcurrencyTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	// 실제 CryptoWatchLock(진짜 Redis)으로 배선된 스프링 빈 — [방어 켠] 테스트 전용.
	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	// [재현] 테스트가 CryptoWatchLock만 바꿔치기해 새 CryptoPriceMoveWatcher를 조립할 때 재사용하는 협력자들.
	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private CryptoPriceSnapshotService cryptoPriceSnapshotService;

	@Autowired
	private PriceMoveCardWriter priceMoveCardWriter;

	@Autowired
	private NewsMatcher newsMatcher;

	@Autowired
	private FeedbackCryptoProperties cryptoProperties;

	@Autowired
	private FeedbackDetectionProperties detectionProperties;

	@Autowired
	private Clock clock;

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

	// 실 LLM 호출 없이 호출 횟수만 mock으로 세기 위해 컨텍스트 전체에서 real bean을 대체한다
	// (CryptoFeedbackBatchIntegrationTest와 같은 방식). 두 테스트가 공유하는 스프링 빈이지만 @MockitoBean은
	// 테스트 메서드 사이에 자동으로 reset된다(같은 클래스의 다른 메서드가 이미 times(1)/times(2)로 독립
	// 검증하는 선례 — CryptoFeedbackBatchIntegrationTest).
	@MockitoBean
	private NarrativeService narrativeService;

	private String symbol;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		symbol = "LOCKR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "락경합코인", BigDecimal.ONE, 5000L, true, NOW));
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
	}

	// CryptoPriceMoveWatcherIntegrationTest의 givenEnoughSnapshotsWithARecentJump와 같은 픽스처 — z-score
	// 게이트를 항상 통과하게 만들어, 두 스레드 모두 반드시 락 시도 지점까지 도달하게 한다.
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

	@Test
	@DisplayName("[방어 켠 상태] 실제 Redis 락으로 두 스레드가 동시에 watch()를 실행해도 카드는 1건만 저장되고 NarrativeService도 1회만 불린다")
	void watchWithRealLockPersistsExactlyOneCardAndCallsNarrativeServiceOnce() throws Exception {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();

		runConcurrently(cryptoPriceMoveWatcher::watch, cryptoPriceMoveWatcher::watch);

		long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
		assertThat(cardCount).isEqualTo(1L);
		verify(narrativeService, times(1)).resolvePriceMoveNarrative(any());
	}

	@Test
	@DisplayName("[방어 비활성/우회 재현] 락이 실제 상호 배제를 하지 않으면(모두 획득에 성공한다고 착각) "
		+ "두 스레드 동시 실행이 카드 2건·NarrativeService 2회 호출로 재현된다")
	void watchWithoutRealMutualExclusionReproducesDuplicateCardsAndNarrativeCalls() throws Exception {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();
		CryptoPriceMoveWatcher watcherWithoutRealLock = new CryptoPriceMoveWatcher(
			instrumentService, cryptoPriceSnapshotService, priceMoveEventRepository, priceMoveCardWriter,
			alwaysSucceedingLockWithFreshTokens(), newsMatcher, narrativeService, cryptoProperties,
			detectionProperties, clock);

		runConcurrently(watcherWithoutRealLock::watch, watcherWithoutRealLock::watch);

		long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
		assertThat(cardCount).isEqualTo(2L);
		verify(narrativeService, times(2)).resolvePriceMoveNarrative(any());
	}

	// tryLock을 호출할 때마다 매번 서로 다른 토큰으로 성공시킨다 — 실제 경합 조정을 전혀 하지 않으면서도
	// 호출부 입장에서는 "이번에도 내가 락을 얻었다"로 보이는, 락 도입 전의 무방비 상태를 그대로 재현한다.
	private static CryptoWatchLock alwaysSucceedingLockWithFreshTokens() {
		CryptoWatchLock lock = mock(CryptoWatchLock.class);
		when(lock.tryLock(any())).thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
		return lock;
	}

	// LimitOrderConcurrencyIntegrationTest의 ready/start CountDownLatch 관례를 그대로 재사용한다 — 두 액션을
	// 준비 완료 후 동시에 출발시켜 실제 경합을 재현한다(둘 다 latch로 실제 동시 실행임을 보장한다).
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
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
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

	@TestConfiguration
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}
	}
}
