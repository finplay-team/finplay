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
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.ExitPlanService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;

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

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

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

	@Test
	void concurrentDuplicateKeyInsertsOnTradeAllocationsFailCleanlyWithoutDeadlock() throws Exception {
		runDuplicateKeyScenario("dupwarmup");

		List<DuplicateKeyMeasurement> measurements = new ArrayList<>();
		for (int i = 0; i < REPEAT; i++) {
			measurements.add(runDuplicateKeyScenario("dup" + i));
		}

		int totalSuccess = measurements.stream().mapToInt(DuplicateKeyMeasurement::successCount).sum();
		int totalDeadlocks = measurements.stream().mapToInt(DuplicateKeyMeasurement::deadlockCount).sum();
		int totalConstraintViolations = measurements.stream()
			.mapToInt(DuplicateKeyMeasurement::constraintViolationCount).sum();
		int totalOtherFailures = measurements.stream().mapToInt(DuplicateKeyMeasurement::otherFailureCount).sum();

		log.info(
			"trade_allocations 같은 키 동시 INSERT 실측 — 시도 {}건 x {}회, 성공={}, deadlock={}, 유니크위반={}, 기타실패={}",
			CONCURRENCY, REPEAT, totalSuccess, totalDeadlocks, totalConstraintViolations, totalOtherFailures);

		assertThat(totalSuccess).isEqualTo(REPEAT);
		assertThat(totalDeadlocks).isZero();
		assertThat(totalOtherFailures).isZero();
	}

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

	private record DuplicateKeyMeasurement(
		int successCount, int deadlockCount, int constraintViolationCount, int otherFailureCount) {
	}

	private record AllocationKey(Long sellTradeId, Long holdingLotId) {
	}

	private AllocationKey fabricateAllocatableSellTradeAndLot(String scenario) {
		User user = createUser(scenario);
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument(scenario.length() > 6 ? scenario.substring(0, 6) : scenario);

		BigDecimal buyPrice = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), buyPrice, LocalDateTime.now(clock));
		BigDecimal buyQuantity = new BigDecimal("10");
		orderService.createOrder(user.getId(), "idem-dupkey-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		Holding holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		Long lotId = holdingLotRepository
			.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(holding.getId(), BigDecimal.ZERO)
			.get(0).getId();

		BigDecimal sellQuantity = new BigDecimal("1");
		String idempotencyKey = "idem-dupkey-sell-" + UUID.randomUUID();
		Order sellOrder = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, sellQuantity, idempotencyKey,
			sha256(idempotencyKey), LocalDateTime.now(clock));
		orderRepository.saveAndFlush(sellOrder);
		Trade sellTrade = Trade.of(
			sellOrder, account, instrument, null, OrderSide.SELL, buyPrice, sellQuantity, 100_000L, 0L, null,
			LocalDateTime.now(clock), LocalDateTime.now(clock));
		tradeRepository.saveAndFlush(sellTrade);

		return new AllocationKey(sellTrade.getId(), lotId);
	}

	private DuplicateKeyMeasurement runDuplicateKeyScenario(String scenario) throws Exception {
		AllocationKey key = fabricateAllocatableSellTradeAndLot(scenario);

		CountDownLatch ready = new CountDownLatch(CONCURRENCY);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < CONCURRENCY; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					Trade sellTrade = tradeRepository.findById(key.sellTradeId()).orElseThrow();
					HoldingLot lot = holdingLotRepository.findById(key.holdingLotId()).orElseThrow();
					tradeAllocationRepository.saveAndFlush(
						TradeAllocation.create(sellTrade, lot, BigDecimal.ONE, 1_000L, 0L, LocalDateTime.now(clock)));
					return null;
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int successCount = 0;
			int deadlockCount = 0;
			int constraintViolationCount = 0;
			int otherFailureCount = 0;
			for (Future<Void> future : futures) {
				try {
					future.get(15, TimeUnit.SECONDS);
					successCount++;
				} catch (ExecutionException e) {
					if (isDeadlock(e.getCause())) {
						deadlockCount++;
					} else if (e.getCause() instanceof DataIntegrityViolationException) {
						constraintViolationCount++;
					} else {
						otherFailureCount++;
						log.warn("같은 키 동시 INSERT 시도 중 예상 밖 예외 발생", e.getCause());
					}
				}
			}
			return new DuplicateKeyMeasurement(successCount, deadlockCount, constraintViolationCount,
				otherFailureCount);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
		}
	}
}
