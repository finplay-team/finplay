// 지정가 체결에서 같은 계좌를 서로 다른 종목 파티션이 동시에 잠그려 할 때 실제로 유의미한 지연이 생기는지 측정한다
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

// LimitOrderFillExecutorRouter(ADR-0024)의 파티션 8개가 동시에 트리거되되, 그중 여러 파티션이 같은 계좌의
// 서로 다른 종목 주문을 동시에 체결하려는 최악의 경우를 fillIfPending 직접 동시 호출로 재현한다. 파티션
// 라우팅·배치(ADR-0025) 자체는 이 측정의 관심사가 아니라서 실행기를 거치지 않고 락 경합만 격리한다.
@SpringBootTest
@Import({TestcontainersConfiguration.class,
	LimitOrderFillAccountLockContentionIntegrationTest.TransactionJoinHarnessConfig.class})
class LimitOrderFillAccountLockContentionIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(LimitOrderFillAccountLockContentionIntegrationTest.class);
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

	@Autowired
	private TransactionJoinHarness transactionJoinHarness;

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

		// ADR-0028 — 격리수준을 READ COMMITTED로 좁혀 적용한 뒤에는 baseline(서로 다른 계좌·종목의 동시
		// 첫 매수)에서 holdings INSERT 데드락이 나지 않아야 한다(수정 전 40건 중 32건 재현, 이 파일 커밋
		// 이력 참고). contended는 애초에 계좌 락으로 직렬화돼 데드락이 나지 않던 시나리오라 함께 확인한다.
		assertThat(contendedDeadlocks).isZero();
		assertThat(baselineDeadlocks).isZero();
		assertThat(contendedOtherFailures).isZero();
		assertThat(baselineOtherFailures).isZero();
	}

	// PR #514 리뷰 차단사항 — fillIfPending 자신의 @Transactional(isolation=READ_COMMITTED) 선언은 이미 열려
	// 있는 트랜잭션에 합류(REQUIRED)할 때 Spring이 조용히 무시한다(validateExistingTransaction 기본값
	// false). 튜토리얼 경로(PracticeOrderSettlementService)가 정확히 이 패턴이었다. 실제 데드락 재현으로
	// 증명하려 했으나(동시 실행 스레드 수에 좌우되는 확률적 재현이라 다른 테스트와 같이 돌 때 0건이 나오는
	// flaky 결과가 실제로 관측됨) 대신 결정론적으로 확인한다 — 같은 중첩 구조(외부 @Transactional이 안쪽
	// @Transactional(isolation=READ_COMMITTED)을 감싸는 것)에서 실제 DB 세션의 격리수준이 무엇인지
	// SELECT @@transaction_isolation으로 직접 읽는다. fillIfPending의 구체적인 비즈니스 로직과 무관하게
	// Spring 트랜잭션 전파 규칙만으로 결정되는 사실이라, 이 대체가 원래 주장을 약화시키지 않는다.
	@Test
	void innerReadCommittedDeclarationIsIgnoredWhenJoiningAnAlreadyOpenDefaultIsolationTransaction() {
		assertThat(transactionJoinHarness.isolationWhenOuterIsDefault()).isEqualTo("REPEATABLE-READ");
		assertThat(transactionJoinHarness.isolationWhenOuterIsReadCommitted()).isEqualTo("READ-COMMITTED");
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

	private Measurement runConcurrentlyAndMeasure(List<Long> orderIds) throws Exception {
		return runConcurrentlyAndMeasure(orderIds, limitOrderFillService::fillIfPending);
	}

	// ready/start 래치로 모든 스레드를 동시에 출발시키고, start 이후 전부 완료(성공이든 실패든)될 때까지의
	// 벽시계 시간을 잰다. 개별 건의 데드락·실패로 측정 자체가 끊기지 않도록 future마다 개별 try/catch한다.
	// action은 기본적으로 limitOrderFillService.fillIfPending이지만, 트랜잭션 합류 재현 테스트는 대신
	// TransactionJoinHarness의 래퍼 메서드를 넘겨 외부 트랜잭션 격리수준만 바꿔 같은 부하를 재사용한다.
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

	// PracticeOrderSettlementService.settleOnTick/settleCurrentRun(수정 전, 외부 트랜잭션 격리수준 미지정)과
	// PracticePriceTickService.advanceTick·PracticeAttemptChartService.tick(수정 후, READ COMMITTED 명시)이
	// fillIfPending을 감싸는 실제 중첩 구조를 최소로 재현하는 테스트 전용 래퍼. Spring 빈으로 등록돼야
	// @Transactional AOP 프록시가 걸린다.
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

		// fillIfPending과 동일한 선언(REQUIRED 전파 + isolation=READ_COMMITTED)이다 — 바깥 트랜잭션에 합류할
		// 때 이 선언이 무시되는지가 검증 대상이므로, 실제 DB 세션의 격리수준을 직접 읽어 확인한다.
		@Transactional(isolation = Isolation.READ_COMMITTED)
		String innerReadCommitted() {
			return jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);
		}
	}
}
