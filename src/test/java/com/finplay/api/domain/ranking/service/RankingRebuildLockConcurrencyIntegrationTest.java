package com.finplay.api.domain.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.store.RankingStore;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RankingRebuildLockConcurrencyIntegrationTest {

	private static final Market MARKET = Market.CRYPTO;
	private static final String LOCK_KEY = "ranking:rebuild:lock:" + MARKET.name();

	@Autowired
	private RankingRebuildService rankingRebuildService;

	@Autowired
	private RankingRebuildLock rankingRebuildLock;

	@Autowired
	private AccountService accountService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private TradeService tradeService;

	@MockitoBean
	private RankingStore rankingStore;

	@BeforeEach
	void setUp() {
		redisTemplate.delete(LOCK_KEY);
		clearInvocations(tradeService, rankingStore);
	}

	@AfterEach
	void cleanUp() {
		redisTemplate.delete(LOCK_KEY);
	}

	@Test
	@DisplayName("[방어 켠 상태] 실제 Redis 락으로 두 스레드가 동시에 rebuild()를 실행해도 재구성 본문은 한 번만 실행된다")
	void rebuildWithRealLockRunsExactlyOnceWhenTwoThreadsRaceForTheSameMarket() throws Exception {
		runConcurrently(
			() -> rankingRebuildService.rebuild(MARKET),
			() -> rankingRebuildService.rebuild(MARKET));

		verify(tradeService, times(1)).getSoldAccountIds(MARKET);
	}

	@Test
	@DisplayName("[방어 비활성/우회 재현] 락이 실제 상호 배제를 하지 않으면(둘 다 획득에 성공한다고 착각) "
		+ "두 스레드 동시 실행이 재구성 본문 2회 실행으로 재현된다")
	void rebuildWithoutRealMutualExclusionReproducesDuplicateExecution() throws Exception {
		when(tradeService.getSoldAccountIds(MARKET)).thenReturn(List.of());
		RankingRebuildService serviceWithoutRealLock = new RankingRebuildService(
			tradeService, accountService, rankingStore, alwaysSucceedingLockWithFreshTokens());

		runConcurrently(
			() -> serviceWithoutRealLock.rebuild(MARKET),
			() -> serviceWithoutRealLock.rebuild(MARKET));

		verify(tradeService, times(2)).getSoldAccountIds(MARKET);
	}

	@Test
	@DisplayName("[결정론적 방어 확인] 테스트 스레드가 실제 락을 먼저 쥔 상태면 rebuild()가 DB를 조회하지 않는다")
	void rebuildSkipsEntirelyWhenTheRealLockIsAlreadyHeld() {
		Optional<String> heldToken = rankingRebuildLock.tryLock(MARKET);
		assertThat(heldToken).isPresent();

		try {
			rankingRebuildService.rebuild(MARKET);

			verify(tradeService, never()).getSoldAccountIds(MARKET);
		} finally {
			rankingRebuildLock.unlock(MARKET, heldToken.get());
		}
	}

	private static RankingRebuildLock alwaysSucceedingLockWithFreshTokens() {
		RankingRebuildLock lock = mock(RankingRebuildLock.class);
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
