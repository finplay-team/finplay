// 지정가 체결에서 같은 계좌를 서로 다른 종목 파티션이 동시에 잠그려 할 때 실제로 유의미한 지연이 생기는지 측정한다
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.service.LimitOrderFillService;
import com.finplay.api.order.service.LimitOrderService;
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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// LimitOrderFillExecutorRouter(ADR-0024)의 파티션 8개가 동시에 트리거되되, 그중 여러 파티션이 같은 계좌의
// 서로 다른 종목 주문을 동시에 체결하려는 최악의 경우를 fillIfPending 직접 동시 호출로 재현한다. 파티션
// 라우팅·배치(ADR-0025) 자체는 이 측정의 관심사가 아니라서 실행기를 거치지 않고 락 경합만 격리한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillAccountLockContentionIntegrationTest {

	private static final Logger log =
		LoggerFactory.getLogger(LimitOrderFillAccountLockContentionIntegrationTest.class);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0, 0);
	// order.limit-fill-executor.partition-count 기본값(8)과 맞춘다 — 한 계좌가 가질 수 있는 최대 동시 경합 수.
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

	@Test
	void accountLockContentionUnderConcurrentCrossInstrumentFillsIsMeasured() throws Exception {
		// 워밍업 1회 — JIT·커넥션 풀 초기화 비용이 첫 실측에 섞이지 않도록 결과는 버린다.
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

		// 정답이 정해진 회귀 테스트가 아니라 실측 도구다 — 두 값이 음수가 아님만 확인하고, 판단은 로그의 수치로 한다.
		assertThat(contendedMedianMs).isGreaterThanOrEqualTo(0L);
		assertThat(baselineMedianMs).isGreaterThanOrEqualTo(0L);
	}

	// 계좌 1개에 서로 다른 종목 CONCURRENCY개의 PENDING 지정가 매수를 걸어두고 전부 동시에 체결한다.
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

	// 서로 다른 계좌 CONCURRENCY개에 각각 종목 1개씩 PENDING 지정가 매수를 걸어두고 전부 동시에 체결한다 —
	// 계좌 row 잠금은 겹치지 않는 기준선이다(단, holdings 신규 INSERT의 인덱스 락은 계좌와 무관하게 겹칠 수 있다).
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

	// 최소주문금액(5,000)을 넉넉히 넘기면서 계좌 기본 현금(10,000,000) 안에서 CONCURRENCY건을 동시에 예약해도
	// 여유가 있도록 수량·가격을 고정한다(건당 예약 약 10,005원 × 8건 ≈ 80,040원).
	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-lock-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		return created.orderId();
	}

	// ready/start 래치로 모든 스레드를 동시에 출발시키고, start 이후 전부 완료(성공이든 실패든)될 때까지의
	// 벽시계 시간을 잰다. 개별 건의 데드락·실패로 측정 자체가 끊기지 않도록 future마다 개별 try/catch한다.
	private Measurement runConcurrentlyAndMeasure(List<Long> orderIds) throws Exception {
		CountDownLatch ready = new CountDownLatch(orderIds.size());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(orderIds.size());
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (Long orderId : orderIds) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					limitOrderFillService.fillIfPending(orderId);
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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
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
}
