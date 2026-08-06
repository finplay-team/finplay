// 만료 쏠림 방어 대조 — 캐시를 켠 채 락만 무력화하면 동시 요청 N건이 전부 원본에 들어가고, 진짜 Redis 락이면 1건만 들어가는 것을 한 클래스에서 나란히 본다 (tasks.md 항목 6, ADR-0015 §4).
package com.finplay.api.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.service.RedisLock;
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

/**
 * <b>여기서 대조하는 것은 락이다 — 캐시가 아니다.</b> 그래서 {@code enabled=false}를 쓰지 않는다. 양쪽 모두
 * 캐시를 켠 채로 두고 <b>락만</b> 무력화한다. 캐시까지 끄면 원본이 N회인 것이 캐시 부재 탓인지 락 부재 탓인지
 * 분리되지 않는다 — 캐시가 없으면 N회인 것은 당연하고 그것은 락에 대해 아무것도 말해 주지 않는다
 * (#244 PR #254 리뷰가 정확히 이 지적이었다).
 *
 * <p><b>요지는 "캐시가 켜져 있는데도 N회"다.</b> 만료 찰나에는 캐시가 비어 있으므로 캐시만으로는 쏠림을 막지
 * 못한다. 그 순간을 막는 것은 락뿐이며, 아래 두 테스트의 차이가 그 사실 자체다.
 *
 * <p><b>배리어를 두는 자리가 두 갈래에서 다르고, 그것이 의도다.</b>
 *
 * <pre>
 * 대조군 로더 <b>안</b>  CyclicBarrier(N) — N개가 모두 원본에 들어간 상태를 결정론적으로 만든다
 * 방어군 호출 <b>직전</b> CyclicBarrier(N) — 겹침만 만든다
 * </pre>
 *
 * <b>방어군의 로더 안에 배리어를 두면 데드락이다</b> — 상호 배제가 동작하면 로더에 1개만 들어오는데
 * {@code CyclicBarrier(N)}는 N개를 기다리므로 영원히 풀리지 않는다.
 *
 * <p>{@code wait-millis}를 5초로 올린다. 기본 300ms는 운영 추정치라, 느린 CI에서 락 보유자가 값을 채우기 전에
 * 대기가 먼저 타임아웃되면 나머지 스레드가 fail-open으로 원본에 내려간다 — 그러면 방어가 깨진 것이 아니라
 * 타이밍 때문에 단정이 깨진다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 여러 스레드가 각자 실제로 경합해야 하고, 애초에 이 테스트는 DB를
 * 쓰지 않는다(원본은 호출 횟수를 세는 로더다). 공유 Redis 싱글턴이라 캐시 키는 {@code @BeforeEach}에서 지운다
 * — 잔여 값이 있으면 첫 조회가 적중해 "N회" 단정이 조용히 무력해진다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
	"feedback.query-cache.enabled=true",
	"feedback.query-cache.wait-millis=5000"})
class FeedbackQueryCacheStampedeIntegrationTest {

	private static final int THREAD_COUNT = 4;

	// 키가 갈리도록 시나리오마다 다른 종목 번호를 쓴다 — 정리가 어긋나도 서로를 오염시키지 않는다.
	private static final Long CONTROL_INSTRUMENT_ID = 990_001L;

	private static final Long DEFENDED_INSTRUMENT_ID = 990_002L;

	private static final Long PREFILLED_INSTRUMENT_ID = 990_003L;

	private static final Long LOCK_HELD_INSTRUMENT_ID = 990_004L;

	private static final Long DOUBLE_CHECK_INSTRUMENT_ID = 990_005L;

	private static final String ORIGIN_TEXT = "원본이 만든 서술";

	private static final String PREFILLED_TEXT = "미리 채워 둔 서술";

	private static final String EARLIER_REQUEST_TEXT = "먼저 락을 쥔 요청이 채운 서술";

	// 방어군에서 락 보유자가 원본을 도는 동안 나머지가 tryLock을 시도하도록 붙잡아 두는 시간. 이것이 없으면
	// 보유자가 배리어 통과 직후 곧바로 채우고 풀어 버려, 나머지가 경합 없이 적중만 하고 끝날 수 있다 —
	// 그러면 "1회"가 상호 배제 덕인지 그냥 직렬로 흘러서인지 구분되지 않는다.
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
		return "feedback:query-cache:crypto-summary:" + instrumentId;
	}

	private static String summaryLockKey(Long instrumentId) {
		return "feedback:query-cache:lock:crypto-summary:" + instrumentId;
	}

	// 이 테스트가 기대는 전제라 명시적으로 확인한다 — 기본값 300ms로 돌면 방어군이 타이밍 때문에 깨질 수 있다.
	@Test
	@DisplayName("[전제] 테스트 프로퍼티로 wait-millis가 넉넉히 올라가 있고 캐시는 켜져 있다")
	void waitMillisIsRaisedAndTheCacheIsOnForBothArms() {
		assertThat(cacheProperties.enabled()).isTrue();
		assertThat(cacheProperties.waitMillis()).isGreaterThanOrEqualTo(5000L);
	}

	// --- 대조군: 락 무력화, 캐시는 켬 ---

	@Test
	@DisplayName("[대조군: 락 무력화, 캐시는 켬] 동시 요청 4건이 전부 원본에 들어가 원본이 4회 불린다")
	void withoutRealMutualExclusionEveryConcurrentRequestReachesTheLoader() throws Exception {
		FeedbackQueryCache cacheWithoutRealLock = new FeedbackQueryCache(
			redisTemplate, alwaysSucceedingLockWithFreshTokens(), objectMapper, clock, cacheProperties,
			newsProperties);
		// 로더 **안**의 배리어 — N개가 전부 원본에 들어간 상태를 결정론적으로 만든다. 없으면 한 스레드가
		// 혼자 다 끝내고 캐시를 채워, 뒤따르는 스레드들이 적중해 버려 우연히 1~2회가 나올 수 있다.
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

	// --- 방어군: 락 켬, 캐시도 켬 ---

	@Test
	@DisplayName("[방어군: 진짜 Redis 락, 캐시도 켬] 같은 동시 요청 4건에서 원본은 1회만 불린다")
	void realRedisLockLetsExactlyOneConcurrentRequestReachTheLoader() throws Exception {
		// 호출 **직전**의 배리어 — 겹침만 만든다. 로더 안에 두면 로더에 1개만 들어와 영원히 대기한다(데드락).
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
		// 원본에 못 들어간 셋도 정상 응답을 받는다. 방어가 "막는다"가 아니라 "한 번만 부르고 나눠 준다"임을 못박는다.
		assertThat(results).allSatisfy(result -> assertThat(result).contains(ORIGIN_TEXT));
		assertThat(redisTemplate.opsForValue().get(summaryKey(DEFENDED_INSTRUMENT_ID))).isEqualTo(ORIGIN_TEXT);
	}

	// --- 결정론적 보조 단정 2개 (타이밍에 기대지 않는다) ---

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

	/*
	 * 락을 다른 쪽이 쥐고 놓지 않는 상황이다 — 조회는 대기하다 타임아웃되면 원본으로 내려가야 한다(fail-open).
	 * 락을 못 얻었다고 응답을 실패시키면 락 자체가 장애가 된다.
	 *
	 * wait-millis만 200ms로 낮춘 별도 캐시를 쓴다. 여기서는 타임아웃이 나는 것이 검증 대상이라 5초를 그대로
	 * 기다릴 이유가 없다 — 위 방어군에서 5초로 올린 이유(타임아웃이 나면 안 된다)와 정반대다.
	 */
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
			// 즉시 내려간 것이 아니라 "대기 후" 내려갔다. 아래 하한이 wait-millis보다 조금 낮은 것은 sleep과
			// nanoTime의 해상도 차이를 흡수하기 위해서다.
			assertThat(elapsedMillis).isGreaterThanOrEqualTo(150L);
			// fail-open 경로는 캐시에 쓰지 않는다 — 락을 쥔 쪽이 채울 새 값을 늦게 깨어난 이 값이 덮어쓸 수 있다.
			assertThat(redisTemplate.hasKey(summaryKey(LOCK_HELD_INSTRUMENT_ID))).isFalse();
		} finally {
			redisLock.unlock(summaryLockKey(LOCK_HELD_INSTRUMENT_ID), heldToken);
		}
	}

	/*
	 * 락 획득 직후의 double-check를 지킨다 (PR 리뷰 [권장 1]).
	 *
	 * 막으려는 순서는 이것이다 — A와 B가 동시에 미스 → A가 락 획득·로더·저장·해제를 마침 → **그 뒤에** B의
	 * tryLock이 성공. 이때 B가 락을 얻었다는 이유로 곧장 로더로 들어가면 원본이 2회 불려 완료 조건
	 * "동시 요청 N건에서 원본 1회"가 깨진다. 대기 경로(awaitCachedValue)는 락을 **못 얻은** 쪽만 타므로
	 * B를 구해 주지 못한다.
	 *
	 * <b>이 순서를 스레드 경합으로 만들려 하지 않는다.</b> 위 대조·방어 테스트는 배리어가 getOrLoad 호출
	 * 직전이라 네 스레드의 첫 read가 사실상 동시에 일어나고, 그러면 "A가 전부 끝낸 뒤 B가 락을 얻는" 순간이
	 * 재현되지 않는다. 대신 **tryLock이 성공을 반환하면서 부수 효과로 캐시를 채우는** mock을 쓴다 —
	 * "락을 얻은 시점에는 이미 값이 있다"가 타이밍 없이 성립한다.
	 *
	 * double-check 세 줄을 지우면 로더가 1회 불리고 반환값이 ORIGIN_TEXT로 바뀌며 캐시까지 덮어써져
	 * 아래 네 단정이 모두 깨진다 — 실제로 지워서 red를 확인했다.
	 */
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
		// 내 로더 값으로 덮어쓰지도 않는다.
		assertThat(redisTemplate.opsForValue().get(valueKey)).isEqualTo(EARLIER_REQUEST_TEXT);
		// 값을 그대로 돌려주고 빠져나가도 락은 푼다 — finally 경로가 살아 있어야 다음 요청이 대기하지 않는다.
		verify(lockUnlockRecorder).unlock(lockKey, issuedToken.get());
	}

	// tryLock이 성공을 반환하기 **직전에** 캐시를 채운다 — 먼저 락을 쥔 요청이 저장·해제까지 마친 뒤에야
	// 내 차례가 온 상황과 호출부 입장에서 구분되지 않는다.
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

	// 위 헬퍼가 만든 mock을 단정에서 다시 봐야 해서 필드로 잡아 둔다.
	private RedisLock lockUnlockRecorder;

	// tryLock을 부를 때마다 서로 다른 토큰으로 성공시킨다 — 상호 배제를 전혀 하지 않으면서 호출부에는
	// "이번에도 내가 락을 얻었다"로 보이는 상태다(#244의 대조군과 같은 수법).
	private static RedisLock alwaysSucceedingLockWithFreshTokens() {
		RedisLock lock = mock(RedisLock.class);
		when(lock.tryLock(anyString(), any(Duration.class)))
			.thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
		when(lock.unlock(anyString(), anyString())).thenReturn(RedisLock.UnlockResult.RELEASED);
		return lock;
	}

	// 배리어가 랑데부를 맡으므로 별도 start 래치를 두지 않는다 — 출발 신호가 두 겹이면 어느 쪽이 실제 겹침을
	// 만들었는지 흐려진다.
	private List<Optional<String>> runConcurrently(Callable<Optional<String>> action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		try {
			List<Future<Optional<String>>> futures = java.util.stream.IntStream.range(0, THREAD_COUNT)
				.mapToObj(index -> executor.submit(action))
				.toList();
			// get()에 넉넉한 타임아웃을 둔다 — 배리어를 잘못 놓아 데드락이 나면 테스트가 영원히 매달리지 않고
			// TimeoutException으로 드러나야 한다.
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
