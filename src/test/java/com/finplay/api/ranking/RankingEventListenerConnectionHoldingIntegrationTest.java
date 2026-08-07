// 매도 트랜잭션의 커넥션과 RankingEventListener.refreshScore(REQUIRES_NEW)의 새 커넥션이 동시에 점유됨을 실측으로 고정하는 통합 테스트다.
package com.finplay.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.ranking.store.RankingStore;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이슈 #270 1번 항목의 회귀 테스트다. {@code @TransactionalEventListener(AFTER_COMMIT)} 콜백은 원 매도
 * 트랜잭션의 커넥션이 풀에 반납되기 전에 실행되므로, {@code RankingService.refreshScore}의
 * {@code REQUIRES_NEW}가 그 반납 전에 새 커넥션을 하나 더 연다 — 요청 스레드 하나가 순간적으로 커넥션
 * 2개를 동시에 쥔다. {@code application.yml}의 {@code maximum-pool-size: 20}이 "요청당 최대 2커넥션"
 * 전제로 잡은 값이므로, 그 전제 자체가 사실인지 여기서 실측으로 고정해 둔다.
 *
 * <p>풀을 정확히 2로 좁혀 두고, {@code RankingStore.addScoreWithRetry}(리스너의 REQUIRES_NEW 트랜잭션 안,
 * Redis 호출 직전) 호출을 래치로 붙잡아 "원 트랜잭션 커넥션(아직 반납 전) + REQUIRES_NEW 커넥션"이 겹치는
 * 순간을 결정론적으로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
	// 이 시나리오(원 트랜잭션 1 + REQUIRES_NEW 리스너 1)가 정확히 채우는 크기로 좁힌다.
	"spring.datasource.hikari.maximum-pool-size=2",
	// 고갈되면 무한 대기가 아니라 빠른 실패로 드러나게 한다.
	"spring.datasource.hikari.connection-timeout=1000"})
class RankingEventListenerConnectionHoldingIntegrationTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoSpyBean
	private RankingStore rankingStore;

	@AfterEach
	void tearDown() {
		reset(rankingStore);
		redisTemplate.delete("ranking:CRYPTO");
	}

	@Test
	void afterCommitListenerHoldsOriginalAndRequiresNewConnectionsSimultaneously() throws Exception {
		Account account = createAccount();

		CountDownLatch enteredRedisCall = new CountDownLatch(1);
		CountDownLatch releaseRedisCall = new CountDownLatch(1);
		doAnswer(invocation -> {
			enteredRedisCall.countDown();
			releaseRedisCall.await(5, TimeUnit.SECONDS);
			return invocation.callRealMethod();
		}).when(rankingStore).addScoreWithRetry(any(), any(), anyLong());

		// 스레드 안에서 난 실패(단정 실패 포함)는 스레드를 조용히 죽일 뿐 이 테스트를 실패시키지 않으므로
		// 직접 붙잡아 join 이후 메인 스레드에서 다시 던진다.
		AtomicReference<Throwable> sellThreadFailure = new AtomicReference<>();
		Thread sellThread = new Thread(() -> {
			try {
				TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
				outerTx.executeWithoutResult(status -> {
					// 원 트랜잭션의 커넥션 점유를 실제로 유도한다(지연 획득이라 조회 한 번은 있어야 한다).
					accountRepository.findById(account.getId()).orElseThrow();
					eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
				});
			} catch (Throwable ex) {
				sellThreadFailure.set(ex);
			}
		});
		sellThread.start();

		try {
			assertThat(enteredRedisCall.await(5, TimeUnit.SECONDS)).isTrue();
			// 이 시점: 원 트랜잭션은 커밋됐지만 AFTER_COMMIT 콜백이 아직 도는 중이라 커넥션이 반납되지 않았고,
			// refreshScore의 REQUIRES_NEW 트랜잭션이 별도 커넥션을 쥔 채 Redis 호출 직전에 멈춰 있다.
			assertThat(activeConnections())
				.as("원 트랜잭션 커넥션(아직 반납 전) + REQUIRES_NEW 리스너 커넥션이 동시에 점유된다")
				.isEqualTo(2);
		} finally {
			releaseRedisCall.countDown();
			sellThread.join(5_000);
		}
		assertThat(sellThreadFailure.get()).isNull();
	}

	private int activeConnections() throws Exception {
		return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
	}

	private Account createAccount() {
		LocalDateTime now = LocalDateTime.now();
		String scenario = "rank-conn-hold-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(
			User.create(scenario + "@finplay.com", "password-hash", scenario, now));
		return accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, now));
	}
}
