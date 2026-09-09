package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
	"feedback.query-cache.enabled=true",
	"feedback.query-cache.wait-millis=5000"})
class FeedbackQueryCacheStampedeIntegrationTest {

	private static final int THREAD_COUNT = 4;

	private static final Long CONTROL_INSTRUMENT_ID = 990_001L;

	private static final Long DEFENDED_INSTRUMENT_ID = 990_002L;

	private static final Long PREFILLED_INSTRUMENT_ID = 990_003L;

	private static final Long LOCK_HELD_INSTRUMENT_ID = 990_004L;

	private static final Long DOUBLE_CHECK_INSTRUMENT_ID = 990_005L;

	private static final Long NEGATIVE_RESULT_INSTRUMENT_ID = 990_006L;

	private static final String ORIGIN_TEXT = "원본이 만든 서술";

	private static final String PREFILLED_TEXT = "미리 채워 둔 서술";

	private static final String EARLIER_REQUEST_TEXT = "먼저 락을 쥔 요청이 채운 서술";

	private static final long LOADER_WORK_MILLIS = 300;

	@Autowired
	private FeedbackQueryCache feedbackQueryCache;

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private Clock clock;

	@Autowired
	private FeedbackQueryCacheProperties cacheProperties;

	@Autowired
	private FeedbackNewsProperties newsProperties;

	@BeforeEach
	void clearQueryCache() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	@AfterEach
	void clearQueryCacheAfterwards() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	private static String summaryKey(Long instrumentId) {
		return "feedback:query-cache:v1:crypto-summary:" + instrumentId;
	}

	private static String summaryLockKey(Long instrumentId) {
		return "feedback:query-cache:lock:v1:crypto-summary:" + instrumentId;
	}

	@Test
	@DisplayName("[전제] 테스트 프로퍼티로 wait-millis가 넉넉히 올라가 있고 캐시는 켜져 있다")
	void waitMillisIsRaisedAndTheCacheIsOnForBothArms() {
		assertThat(cacheProperties.enabled()).isTrue();
		assertThat(cacheProperties.waitMillis()).isGreaterThanOrEqualTo(5000L);
	}


	@Test
	@DisplayName("[대조군: 락 무력화, 캐시는 켬] 동시 요청 4건이 전부 원본에 들어가 원본이 4회 불린다")
	void withoutRealMutualExclusionEveryConcurrentRequestReachesTheLoader() throws Exception {
		FeedbackQueryCache cacheWithoutRealLock = new FeedbackQueryCache(
			redisTemplate, alwaysSucceedingLockWithFreshTokens(), objectMapper, clock, cacheProperties,
			newsProperties);
		CyclicBarrier allInsideTheLoader = new CyclicBarrier(THREAD_COUNT);
		AtomicInteger loaderCalls = new AtomicInteger();

		List<Optional<String>> results = runConcurrently(
			() -> cacheWithoutRealLock.getOrLoadCryptoSummaryText(CONTROL_INSTRUMENT_ID, () -> {
				loaderCalls.incrementAndGet();
				awaitAt(allInsideTheLoader);
				return Optional.of(ORIGIN_TEXT);
			}));

		assertThat(loaderCalls)
			.as("캐시가 켜져 있는데도 4회다 — 만료 찰나에는 캐시가 비어 있어 캐시만으로는 쏠림을 못 막는다")
			.hasValue(THREAD_COUNT);
		assertThat(results).allSatisfy(result -> assertThat(result).contains(ORIGIN_TEXT));
	}


	@Test
	@DisplayName("[방어군: 진짜 Redis 락, 캐시도 켬] 같은 동시 요청 4건에서 원본은 1회만 불린다")
	void realRedisLockLetsExactlyOneConcurrentRequestReachTheLoader() throws Exception {
		CyclicBarrier atTheGate = new CyclicBarrier(THREAD_COUNT);
		AtomicInteger loaderCalls = new AtomicInteger();

		List<Optional<String>> results = runConcurrently(() -> {
			awaitAt(atTheGate);
			return feedbackQueryCache.getOrLoadCryptoSummaryText(DEFENDED_INSTRUMENT_ID, () -> {
				loaderCalls.incrementAndGet();
				sleepQuietly(LOADER_WORK_MILLIS);
				return Optional.of(ORIGIN_TEXT);
			});
		});

		assertThat(loaderCalls)
			.as("대조군과 같은 조건에서 락만 진짜로 바꿨다 — 그 하나가 4회를 1회로 만든다")
			.hasValue(1);
		assertThat(results).allSatisfy(result -> assertThat(result).contains(ORIGIN_TEXT));
		assertThat(redisTemplate.opsForValue().get(summaryKey(DEFENDED_INSTRUMENT_ID))).isEqualTo(ORIGIN_TEXT);
	}


	@Test
	@DisplayName("[조기 이탈] 음성 결과에서 동시 4건이 wait-millis를 태우지 않고 곧바로 원본으로 내려간다")
	void concurrentRequestsOnANegativeResultLeaveTheWaitEarly() throws Exception {
		CyclicBarrier atTheGate = new CyclicBarrier(THREAD_COUNT);
		AtomicInteger loaderCalls = new AtomicInteger();

		long startedAt = System.nanoTime();
		List<Optional<String>> results = runConcurrently(() -> {
			awaitAt(atTheGate);
			return feedbackQueryCache.getOrLoadCryptoSummaryText(NEGATIVE_RESULT_INSTRUMENT_ID, () -> {
				loaderCalls.incrementAndGet();
				return Optional.empty();
			});
		});
		long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

		assertThat(results).allSatisfy(result -> assertThat(result).isEmpty());
		assertThat(elapsedMillis)
			.as("보유자가 빈손으로 끝났는데도 wait-millis(%d)를 다 태웠다면 조기 이탈이 동작하지 않은 것이다",
				cacheProperties.waitMillis())
			.isLessThan(1500L);
		assertThat(loaderCalls)
			.as("스레드마다 정확히 한 번씩 — 조기 이탈이 호출 수를 바꾸지는 않는다")
			.hasValue(THREAD_COUNT);
		assertThat(redisTemplate.hasKey(summaryKey(NEGATIVE_RESULT_INSTRUMENT_ID))).isFalse();
	}


	@Test
	@DisplayName("[보조] 캐시를 미리 채워 두면 동시 요청 4건에서 원본이 한 번도 불리지 않는다")
	void prefilledCacheKeepsEveryConcurrentRequestAwayFromTheLoader() throws Exception {
		redisTemplate.opsForValue()
			.set(summaryKey(PREFILLED_INSTRUMENT_ID), PREFILLED_TEXT, Duration.ofMinutes(5));
		CyclicBarrier atTheGate = new CyclicBarrier(THREAD_COUNT);
		AtomicInteger loaderCalls = new AtomicInteger();

		List<Optional<String>> results = runConcurrently(() -> {
			awaitAt(atTheGate);
			return feedbackQueryCache.getOrLoadCryptoSummaryText(PREFILLED_INSTRUMENT_ID, () -> {
				loaderCalls.incrementAndGet();
				return Optional.of(ORIGIN_TEXT);
			});
		});

		assertThat(loaderCalls).hasValue(0);
		assertThat(results).allSatisfy(result -> assertThat(result).contains(PREFILLED_TEXT));
	}

	@Test
	@DisplayName("[보조] 테스트 스레드가 락을 쥔 채면 대기 후 fail-open으로 원본 1회 + 응답 정상이고 캐시에 쓰지 않는다")
	void failsOpenAfterWaitingWhenSomeoneElseHoldsTheLock() {
		FeedbackQueryCache cacheWithShortWait = new FeedbackQueryCache(
			redisTemplate, redisLock, objectMapper, clock,
			new FeedbackQueryCacheProperties(true, 5000, 200, 20), newsProperties);
		String heldToken = redisLock
			.tryLock(summaryLockKey(LOCK_HELD_INSTRUMENT_ID), Duration.ofSeconds(10))
			.orElseThrow();
		AtomicInteger loaderCalls = new AtomicInteger();

		try {
			long startedAt = System.nanoTime();
			Optional<String> result = cacheWithShortWait.getOrLoadCryptoSummaryText(
				LOCK_HELD_INSTRUMENT_ID, () -> {
					loaderCalls.incrementAndGet();
					return Optional.of(ORIGIN_TEXT);
				});
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

			assertThat(result).as("락 보유 중에도 조회가 실패하지 않는다").contains(ORIGIN_TEXT);
			assertThat(loaderCalls).hasValue(1);
			assertThat(elapsedMillis).isGreaterThanOrEqualTo(150L);
			assertThat(redisTemplate.hasKey(summaryKey(LOCK_HELD_INSTRUMENT_ID))).isFalse();
		} finally {
			redisLock.unlock(summaryLockKey(LOCK_HELD_INSTRUMENT_ID), heldToken);
		}
	}

	@Test
	@DisplayName("[결정론] 락을 얻은 시점에 캐시가 이미 채워져 있으면 로더를 부르지 않고 그 값을 쓴다")
	void doubleChecksTheCacheRightAfterAcquiringTheLockSoTheLoaderIsNeverCalled() {
		String valueKey = summaryKey(DOUBLE_CHECK_INSTRUMENT_ID);
		String lockKey = summaryLockKey(DOUBLE_CHECK_INSTRUMENT_ID);
		AtomicReference<String> issuedToken = new AtomicReference<>();
		AtomicInteger loaderCalls = new AtomicInteger();
		FeedbackQueryCache cacheWhoseLockArrivesLate = new FeedbackQueryCache(
			redisTemplate, lockGrantedAfterSomeoneElseAlreadyFilled(valueKey, lockKey, issuedToken),
			objectMapper, clock, cacheProperties, newsProperties);

		Optional<String> result = cacheWhoseLockArrivesLate.getOrLoadCryptoSummaryText(
			DOUBLE_CHECK_INSTRUMENT_ID, () -> {
				loaderCalls.incrementAndGet();
				return Optional.of(ORIGIN_TEXT);
			});

		assertThat(loaderCalls).as("먼저 온 요청이 이미 채웠으므로 원본을 두 번째로 부르면 안 된다").hasValue(0);
		assertThat(result).contains(EARLIER_REQUEST_TEXT);
		assertThat(redisTemplate.opsForValue().get(valueKey)).isEqualTo(EARLIER_REQUEST_TEXT);
		verify(lockUnlockRecorder).unlock(lockKey, issuedToken.get());
	}

	private RedisLock lockGrantedAfterSomeoneElseAlreadyFilled(
		String valueKey, String lockKey, AtomicReference<String> issuedToken) {
		lockUnlockRecorder = mock(RedisLock.class);
		when(lockUnlockRecorder.tryLock(eq(lockKey), any(Duration.class))).thenAnswer(invocation -> {
			redisTemplate.opsForValue().set(valueKey, EARLIER_REQUEST_TEXT, Duration.ofMinutes(5));
			String token = UUID.randomUUID().toString();
			issuedToken.set(token);
			return Optional.of(token);
		});
		when(lockUnlockRecorder.unlock(anyString(), anyString())).thenReturn(RedisLock.UnlockResult.RELEASED);
		return lockUnlockRecorder;
	}

	private RedisLock lockUnlockRecorder;

	private static RedisLock alwaysSucceedingLockWithFreshTokens() {
		RedisLock lock = mock(RedisLock.class);
		when(lock.tryLock(anyString(), any(Duration.class)))
			.thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
		when(lock.unlock(anyString(), anyString())).thenReturn(RedisLock.UnlockResult.RELEASED);
		return lock;
	}

	private List<Optional<String>> runConcurrently(Callable<Optional<String>> action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		try {
			List<Future<Optional<String>>> futures = java.util.stream.IntStream.range(0, THREAD_COUNT)
				.mapToObj(index -> executor.submit(action))
				.toList();
			List<Optional<String>> results = new java.util.ArrayList<>();
			for (Future<Optional<String>> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}

	private static void awaitAt(CyclicBarrier barrier) {
		try {
			barrier.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("배리어 대기 중 인터럽트", ex);
		} catch (Exception ex) {
			throw new IllegalStateException("배리어에서 모이지 못했다", ex);
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
