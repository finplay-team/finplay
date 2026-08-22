// OCO 손절·익절이 여러 계좌·종목에서 동시에 트리거될 때 trade_allocations의 새 유니크 제약(V57)이 INSERT
// 데드락을 일으키는지 측정한다
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
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.ExitPlanService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

// V57(trade_allocations UNIQUE(sell_trade_id, holding_lot_id)) 추가 이후 걱정한 시나리오를 그대로
// 재현한다 — 서로 다른 계좌·종목의 OCO 익절이 거의 동시에 트리거되면, ExitPlanFillService.fillIfPending이
// 기본 @Transactional(REPEATABLE READ, ADR-0028의 READ COMMITTED 완화 대상이 아니다)로 각자 holding을 잠그고
// trade_allocations에 INSERT한다. 이 INSERT들의 (sell_trade_id, holding_lot_id) 값은 서로 다르지만, trades·
// holding_lots 양쪽 다 전역 AUTO_INCREMENT라 거의 동시에 커밋되는 이 시나리오에서는 값이 서로 인접한다 — 이
// 조건이 홀딩 INSERT 데드락(ADR-0028)과 같은 종류의 갭 락 경합을 trade_allocations 쪽에서도 일으키는지가
// 관심사다. LimitOrderFillAccountLockContentionIntegrationTest의 ready/start 동시 실행·데드락 계수 패턴을
// 그대로 재사용한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TradeAllocationInsertLockContentionIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(TradeAllocationInsertLockContentionIntegrationTest.class);
	private static final int CONCURRENCY = 8;
	private static final int REPEAT = 5;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private ExitPlanRepository exitPlanRepository;

	@Autowired
	private ExitPlanService exitPlanService;

	@Autowired
	private ExitPlanFillService exitPlanFillService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private Clock clock;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	@Test
	void concurrentExitPlanFillsAcrossDifferentHoldingsDoNotDeadlockOnTradeAllocationsInsert() throws Exception {
		// 워밍업 1회 — JIT·커넥션 풀 초기화 비용이 첫 실측에 섞이지 않도록 결과는 버린다.
		runScenario("warmup");

		List<Measurement> measurements = new ArrayList<>();
		for (int i = 0; i < REPEAT; i++) {
			measurements.add(runScenario("run" + i));
		}

		long medianMs = median(measurements.stream().map(Measurement::elapsedMs).toList());
		int totalDeadlocks = measurements.stream().mapToInt(Measurement::deadlockCount).sum();
		int totalOtherFailures = measurements.stream().mapToInt(Measurement::otherFailureCount).sum();

		log.info(
			"trade_allocations INSERT 락 경합 실측 — 서로 다른 holding {}개 동시 OCO 익절 median={}ms raw={}, "
				+ "deadlock={}, 기타실패={}",
			CONCURRENCY, medianMs, measurements.stream().map(Measurement::elapsedMs).toList(), totalDeadlocks,
			totalOtherFailures);

		assertThat(totalDeadlocks).isZero();
		assertThat(totalOtherFailures).isZero();
	}

	// 서로 다른 계좌 CONCURRENCY개에 각각 종목 1개씩 보유·OCO 익절 예약을 걸어두고 전부 동시에 트리거한다.
	private Measurement runScenario(String scenario) throws Exception {
		List<Long> exitPlanIds = new ArrayList<>();
		BigDecimal takeProfitPrice = new BigDecimal("110000");
		for (int i = 0; i < CONCURRENCY; i++) {
			exitPlanIds.add(createHoldingWithTakeProfitExitPlan(scenario + i, takeProfitPrice));
		}
		return runConcurrentlyAndMeasure(exitPlanIds, takeProfitPrice);
	}

	private Long createHoldingWithTakeProfitExitPlan(String scenario, BigDecimal takeProfitPrice) {
		User user = createUser(scenario);
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument(scenario.length() > 6 ? scenario.substring(0, 6) : scenario);

		BigDecimal buyPrice = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), buyPrice, LocalDateTime.now(clock));
		BigDecimal buyQuantity = new BigDecimal("10");
		orderService.createOrder(user.getId(), "idem-lockcontention-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		Holding holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		BigDecimal reservedQuantity = new BigDecimal("2");
		BigDecimal stopLossPrice = new BigDecimal("90000");
		ExitPlanCreateRequest createRequest = new ExitPlanCreateRequest(
			null, null, null, holding.getId(), reservedQuantity, ExitPriceType.PRICE, stopLossPrice, takeProfitPrice,
			null, null);
		ExitPlanResponse created = exitPlanService.create(user.getId(), UUID.randomUUID().toString(), createRequest);
		return created.id();
	}

	// ready/start 래치로 모든 스레드를 동시에 출발시키고, start 이후 전부 완료(성공이든 실패든)될 때까지의
	// 벽시계 시간을 잰다. 개별 건의 데드락·실패로 측정 자체가 끊기지 않도록 future마다 개별 try/catch한다.
	private Measurement runConcurrentlyAndMeasure(List<Long> exitPlanIds, BigDecimal triggerPrice) throws Exception {
		CountDownLatch ready = new CountDownLatch(exitPlanIds.size());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(exitPlanIds.size());
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (Long exitPlanId : exitPlanIds) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					exitPlanFillService.fillIfPending(exitPlanId, triggerPrice);
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
						log.warn("OCO 익절 체결 시도 중 데드락 외 예외 발생", e.getCause());
					}
				}
			}
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			for (Long exitPlanId : exitPlanIds) {
				ExitPlanStatus status = exitPlanRepository.findById(exitPlanId).orElseThrow().getStatus();
				// ExitPlanFillService.fillIfPending에는 조용히 빠져나가는 return이 세 군데 있다(plan 없음 /
				// !isPending() / triggeredType == null) — 이 중 하나라도 걸리면 trade_allocations에 INSERT가
				// 일어나지 않은 채 데드락 0건이 통과할 수 있으므로 로그가 아니라 어서션으로 막는다.
				assertThat(status).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
			}
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
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, LocalDateTime.now(clock)));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true,
				LocalDateTime.now(clock)));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
