package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.store.RankingStore;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=2",
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

		AtomicReference<Throwable> sellThreadFailure = new AtomicReference<>();
		Thread sellThread = new Thread(() -> {
			try {
				TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
				outerTx.executeWithoutResult(status -> {
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
