package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class,
	LimitOrderFillAccountLockContentionIntegrationTest.TransactionJoinHarnessConfig.class})
class LimitOrderFillAccountLockContentionIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(LimitOrderFillAccountLockContentionIntegrationTest.class);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0, 0);
	private static final int CONCURRENCY = 8;
	private static final int REPEAT = 5;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

	@Autowired
	private TransactionJoinHarness transactionJoinHarness;

	@Test
	void accountLockContentionUnderConcurrentCrossInstrumentFillsIsMeasured() throws Exception {
		runContendedScenario();
		runBaselineScenario();

		List<Measurement> contended = new ArrayList<>();
		List<Measurement> baseline = new ArrayList<>();
		for (int i = 0; i < REPEAT; i++) {
			contended.add(runContendedScenario());
			baseline.add(runBaselineScenario());
		}

		long contendedMedianMs = median(contended.stream().map(Measurement::elapsedMs).toList());
		long baselineMedianMs = median(baseline.stream().map(Measurement::elapsedMs).toList());
		int contendedDeadlocks = contended.stream().mapToInt(Measurement::deadlockCount).sum();
		int baselineDeadlocks = baseline.stream().mapToInt(Measurement::deadlockCount).sum();
		int contendedOtherFailures = contended.stream().mapToInt(Measurement::otherFailureCount).sum();
		int baselineOtherFailures = baseline.stream().mapToInt(Measurement::otherFailureCount).sum();

		log.info(
			"계좌 락 경합 실측 — contended(계좌 1개·종목 {}개 동시 체결) median={}ms raw={}, deadlock={}, 기타실패={}",
			CONCURRENCY, contendedMedianMs, contended.stream().map(Measurement::elapsedMs).toList(),
			contendedDeadlocks, contendedOtherFailures);
		log.info(
			"계좌 락 경합 실측 — baseline(계좌 {}개·종목 각 1개 동시 체결) median={}ms raw={}, deadlock={}, 기타실패={}",
			CONCURRENCY, baselineMedianMs, baseline.stream().map(Measurement::elapsedMs).toList(),
			baselineDeadlocks, baselineOtherFailures);

		assertThat(contendedDeadlocks).isZero();
		assertThat(baselineDeadlocks).isZero();
		assertThat(contendedOtherFailures).isZero();
		assertThat(baselineOtherFailures).isZero();
	}

	@Test
	void innerReadCommittedDeclarationIsIgnoredWhenJoiningAnAlreadyOpenDefaultIsolationTransaction() {
		assertThat(transactionJoinHarness.isolationWhenOuterIsDefault()).isEqualTo("REPEATABLE-READ");
		assertThat(transactionJoinHarness.isolationWhenOuterIsReadCommitted()).isEqualTo("READ-COMMITTED");
	}

	private Measurement runContendedScenario() throws Exception {
		User user = createUser("contended");
		Account account = createAccount(user);
		List<Long> orderIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENCY; i++) {
			Instrument instrument = createCryptoInstrument("CTD" + i);
			orderIds.add(createPendingLimitBuy(account, instrument));
		}
		return runConcurrentlyAndMeasure(orderIds);
	}

	private Measurement runBaselineScenario() throws Exception {
		List<Long> orderIds = new ArrayList<>();
		for (int i = 0; i < CONCURRENCY; i++) {
			User user = createUser("baseline" + i);
			Account account = createAccount(user);
			Instrument instrument = createCryptoInstrument("BSL" + i);
			orderIds.add(createPendingLimitBuy(account, instrument));
		}
		return runConcurrentlyAndMeasure(orderIds);
	}

	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-lock-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		return created.orderId();
	}

	private Measurement runConcurrentlyAndMeasure(List<Long> orderIds) throws Exception {
		return runConcurrentlyAndMeasure(orderIds, limitOrderFillService::fillIfPending);
	}

	private Measurement runConcurrentlyAndMeasure(List<Long> orderIds, Consumer<Long> action) throws Exception {
		CountDownLatch ready = new CountDownLatch(orderIds.size());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(orderIds.size());
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (Long orderId : orderIds) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					action.accept(orderId);
					return null;
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			long startedAt = System.nanoTime();
			start.countDown();

			int deadlockCount = 0;
			int otherFailureCount = 0;
			for (Future<Void> future : futures) {
				try {
					future.get(15, TimeUnit.SECONDS);
				} catch (ExecutionException e) {
					if (isDeadlock(e.getCause())) {
						deadlockCount++;
					} else {
						otherFailureCount++;
						log.warn("체결 시도 중 데드락 외 예외 발생", e.getCause());
					}
				}
			}
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
			return new Measurement(elapsedMs, deadlockCount, otherFailureCount);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private boolean isDeadlock(Throwable throwable) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message != null && message.contains("Deadlock")) {
				return true;
			}
		}
		return false;
	}

	private record Measurement(long elapsedMs, int deadlockCount, int otherFailureCount) {
	}

	private long median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		sorted.sort(Long::compareTo);
		return sorted.get(sorted.size() / 2);
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@TestConfiguration
	static class TransactionJoinHarnessConfig {

		@Bean
		TransactionJoinHarness transactionJoinHarness(JdbcTemplate jdbcTemplate) {
			return new TransactionJoinHarness(jdbcTemplate);
		}
	}

	static class TransactionJoinHarness {

		private final JdbcTemplate jdbcTemplate;

		TransactionJoinHarness(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Transactional
		String isolationWhenOuterIsDefault() {
			return innerReadCommitted();
		}

		@Transactional(isolation = Isolation.READ_COMMITTED)
		String isolationWhenOuterIsReadCommitted() {
			return innerReadCommitted();
		}

		@Transactional(isolation = Isolation.READ_COMMITTED)
		String innerReadCommitted() {
			return jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
		}
	}
}
