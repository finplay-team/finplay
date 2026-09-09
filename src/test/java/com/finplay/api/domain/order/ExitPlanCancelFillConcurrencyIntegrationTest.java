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
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.ExitPlanCancelService;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.ExitPlanService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExitPlanCancelFillConcurrencyIntegrationTest {

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
	private ExitPlanCancelService exitPlanCancelService;

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
	void cancelAndTriggerFillRaceForPendingExitPlanResultInExactlyOneWinnerWithConsistentReservationLedger()
		throws Exception {
		User user = createUser("exit-plan-cancel-fill-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("EXPCXF");

		BigDecimal marketPrice = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), marketPrice, LocalDateTime.now(clock));
		BigDecimal buyQuantity = new BigDecimal("10");
		orderService.createOrder(user.getId(), "idem-expcxf-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal quantityBefore = holding.getQuantity();

		BigDecimal reservedQuantity = new BigDecimal("2");
		BigDecimal stopLossPrice = new BigDecimal("90000");
		BigDecimal takeProfitPrice = new BigDecimal("110000");
		ExitPlanCreateRequest createRequest = new ExitPlanCreateRequest(
			null, null, null, holding.getId(), reservedQuantity, ExitPriceType.PRICE, stopLossPrice, takeProfitPrice,
			null, null);
		ExitPlanResponse created = exitPlanService.create(
			user.getId(), UUID.randomUUID().toString(), createRequest);
		Long exitPlanId = created.id();

		Holding afterCreate = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(afterCreate.getReservedQuantity()).isEqualByComparingTo(reservedQuantity);

		AtomicReference<Exception> cancelException = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					exitPlanCancelService.cancel(user.getId(), exitPlanId);
				} catch (Exception ex) {
					cancelException.set(ex);
				}
			},
			() -> exitPlanFillService.fillIfPending(exitPlanId, takeProfitPrice));

		ExitPlan finalPlan = exitPlanRepository.findById(exitPlanId).orElseThrow();
		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		if (finalPlan.getStatus() == ExitPlanStatus.FILLED_TAKE_PROFIT) {
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.EXIT_PLAN_NOT_PENDING));
			assertThat(finalPlan.getTriggeredOrder()).isNotNull();
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(reservedQuantity);
		} else {
			assertThat(finalPlan.getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(finalPlan.getTriggeredOrder()).isNull();
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		}
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
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
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
