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
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillBatchMarketOrderDeadlockIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);
	private static final int ACCOUNT_COUNT = 6;
	private static final int REPEAT = 5;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

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
	void bulkLockChunkAndConcurrentMarketOrdersOnSharedAccountsCompleteWithoutDeadlock() throws Exception {
		for (int i = 0; i < REPEAT; i++) {
			runOnceAndAssertNoDeadlock();
		}
	}

	private void runOnceAndAssertNoDeadlock() throws Exception {
		List<Account> accounts = new ArrayList<>();
		for (int i = 0; i < ACCOUNT_COUNT; i++) {
			User user = createUser("mkt-" + i);
			accounts.add(createAccount(user));
		}
		Instrument instrumentA = createCryptoInstrument("BLKA");
		BigDecimal marketPrice = new BigDecimal("1000000");
		priceStore.saveTick(instrumentA.getSymbol(), marketPrice, LocalDateTime.now(clock));

		List<Long> chunkOrderIds = new ArrayList<>();
		for (Account account : accounts) {
			chunkOrderIds.add(createPendingLimitBuy(account, instrumentA));
		}
		List<Account> overlappingAccounts = accounts.subList(ACCOUNT_COUNT / 2, ACCOUNT_COUNT);

		CountDownLatch ready = new CountDownLatch(1 + overlappingAccounts.size());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(1 + overlappingAccounts.size());
		try {
			Future<Void> bulkLockFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				limitOrderFillService.fillBatch(chunkOrderIds);
				return null;
			});
			List<Future<OrderResponse>> marketOrderFutures = new ArrayList<>();
			for (Account account : overlappingAccounts) {
				marketOrderFutures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return orderService.createOrder(
						account.getUser().getId(), "idem-blk-market-" + UUID.randomUUID(),
						new OrderCreateRequest(
							Market.CRYPTO, instrumentA.getId(), OrderSide.BUY, "MARKET", new BigDecimal("0.01")));
				}));
			}

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertCompletesWithoutDeadlock(bulkLockFuture);
			for (Future<OrderResponse> future : marketOrderFutures) {
				OrderResponse response = assertCompletesWithoutDeadlock(future);
				assertThat(response.status()).isEqualTo("FILLED");
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		for (Long orderId : chunkOrderIds) {
			assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		}
	}

	private <T> T assertCompletesWithoutDeadlock(Future<T> future) throws InterruptedException, TimeoutException {
		try {
			return future.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError("체결 또는 주문 처리가 데드락 또는 예외로 실패했다: " + e.getCause(), e.getCause());
		}
	}

	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-blk-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		return created.orderId();
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
}
