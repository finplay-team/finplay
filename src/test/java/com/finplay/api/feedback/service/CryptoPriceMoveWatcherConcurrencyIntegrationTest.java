// CryptoPriceMoveWatcher.watchOne()의 종목 단위 Redis 락(ADR-0014)이 다중 인스턴스 동시 실행에서 카드 중복·NarrativeService 중복 호출을 막는지 실제 Redis·MySQL로 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

// tasks-244.md 항목 3 — 락(ADR-0014)이 실제로 다중 인스턴스 경합을 막는지는 mock CryptoWatchLock으로는 증명할 수
// 없다(같은 프로세스의 mock은 경합 자체가 생기지 않는다). 이 클래스만 실제 Redis·MySQL(Testcontainers)로 같은
// 종목·같은 시각 조건을 두 스레드에서 동시에 태워 카드 1건·NarrativeService 1회 호출을 확인한다. σ 계산·쿨다운·
// 자정 등 나머지 오케스트레이션은 CryptoPriceMoveWatcherTest(단위)·CryptoPriceMoveWatcherIntegrationTest(단일
// 스레드 종단)가 이미 본다 — 여기서는 동시성 하나만 본다.
//
// @Transactional을 쓰지 않는다 — 두 스레드가 각자 다른 커넥션에서 락을 다퉈야 하는데 테스트 메서드를
// @Transactional로 감싸면 그 안에서 만든 픽스처가 다른 스레드에는 커밋된 것으로 보이지 않을 수 있다
// (LimitOrderConcurrencyIntegrationTest와 같은 방침 — saveAndFlush로 즉시 커밋하고 @AfterEach로 직접 정리한다).
@SpringBootTest
@Import({TestcontainersConfiguration.class,
	CryptoPriceMoveWatcherConcurrencyIntegrationTest.FixedClockTestConfig.class})
class CryptoPriceMoveWatcherConcurrencyIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

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
	// (CryptoFeedbackBatchIntegrationTest와 같은 방식).
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
	@DisplayName("같은 종목·같은 시각 조건에서 watch()를 두 스레드가 동시에 실행해도 카드는 1건만 저장되고 NarrativeService도 1회만 불린다")
	void watchExecutedByTwoThreadsConcurrentlyPersistsExactlyOneCardAndCallsNarrativeServiceOnce() throws Exception {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();

		runConcurrently(cryptoPriceMoveWatcher::watch, cryptoPriceMoveWatcher::watch);

		long cardCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrument.getId(), Market.CRYPTO, NOW.toLocalDate());
		assertThat(cardCount).isEqualTo(1L);
		verify(narrativeService, times(1)).resolvePriceMoveNarrative(any());
	}

	// LimitOrderConcurrencyIntegrationTest의 ready/start CountDownLatch 관례를 그대로 재사용한다 — 두 액션을
	// 준비 완료 후 동시에 출발시켜 실제 락 경합을 재현한다.
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
