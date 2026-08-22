// RankingRebuildLock(이슈 #539)이 블루-그린 두 인스턴스의 같은 시장 동시 재구성을 실제로 막는지, 방어가 없으면 그 중복 실행이 실제로 재현되는지를 한 클래스에서 대조한다.
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

// [방어 켠 상태]가 먼저 "실제 Redis 락이 두 스레드 중 하나만 통과시킨다"를 증명하고, [재현 대조]가 락이 실제
// 상호 배제를 하지 않으면 둘 다 통과한다는 것을 대조한다 — CryptoWatchLockConcurrencyIntegrationTest(ADR-0014)와
// 같은 구성이다.
//
// SET NX가 Redis에서 원자적이라 두 스레드의 실제 타이밍이 어떻게 겹치든 결과는 항상 결정론적이다(정확히 하나만
// 획득) — CryptoWatchLock 쪽처럼 애플리케이션 내부 로직(쿨다운 확인 등) 사이의 경합 창을 CyclicBarrier로
// 강제로 벌릴 필요가 없다.
//
// TradeService·RankingStore를 @MockitoBean으로 바꿔치기한다 — 이 테스트가 보는 것은 "락 게이트를 통과한 호출이
// 몇 번인가"이지 실데이터 재구성 결과가 아니고, 실제 RankingStore를 그대로 두면 공유 Testcontainers Redis의
// 살아있는 ranking:CRYPTO 키를 건드려 다른 통합 테스트(RankingRebuildIntegrationTest)의 픽스처와 간섭한다.
// AccountService는 mock하지 않는다 — TradeService가 빈 목록을 돌려주는 한(스텁하지 않은 @MockitoBean 기본값)
// accountService.getAccountsByIds는 애초에 호출되지 않으므로 real/mock 여부가 결과에 영향을 주지 않는다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RankingRebuildLockConcurrencyIntegrationTest {

	private static final Market MARKET = Market.CRYPTO;
	private static final String LOCK_KEY = "ranking:rebuild:lock:" + MARKET.name();

	// 실제 Redis 락으로 배선된 스프링 빈 — [방어 켠]·[결정론적 방어 확인] 테스트 전용.
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
		// 공유 Redis는 Spring Context보다 오래 살아 다른 테스트가 남긴 락이 있을 수 있다 — 이 테스트가 쓸 키를
		// 먼저 비운다(CryptoWatchLockConcurrencyIntegrationTest와 같은 이유).
		redisTemplate.delete(LOCK_KEY);
		// @MockitoBean은 테스트 메서드 사이에 호출 이력을 자동으로 지워주지 않는다 — 이전 테스트가 쌓아 둔
		// times(1)/times(2)/never() 판단이 이번 테스트의 호출만으로 이뤄지도록 명시적으로 비운다(실제로 이걸
		// 빼고 돌렸다가 [결정론적 방어 확인] 테스트가 앞선 테스트의 누적 호출 때문에 실패하는 것을 확인했다).
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

	// 타이밍(스레드 겹침)에 의존하지 않고 "락이 실제로 재구성을 막는다"를 증명한다 — 테스트 스레드가 실제
	// 스프링 빈(진짜 Redis)으로 먼저 락을 쥔 채로 rebuild()를 동시성 없이 직접 호출해, 락이 있으면 그 즉시(DB
	// 조회 전) 건너뛴다는 것을 단정한다.
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

	// tryLock을 호출할 때마다 매번 서로 다른 토큰으로 성공시킨다 — 실제 경합 조정을 전혀 하지 않으면서도
	// 호출부 입장에서는 "이번에도 내가 락을 얻었다"로 보이는, 락 도입 전의 무방비 상태를 그대로 재현한다.
	private static RankingRebuildLock alwaysSucceedingLockWithFreshTokens() {
		RankingRebuildLock lock = mock(RankingRebuildLock.class);
		when(lock.tryLock(any())).thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
		return lock;
	}

	// LimitOrderConcurrencyIntegrationTest·CryptoWatchLockConcurrencyIntegrationTest의 ready/start
	// CountDownLatch 관례를 그대로 재사용한다 — 두 액션을 준비 완료 후 동시에 출발시켜 실제 경합을 재현한다.
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
