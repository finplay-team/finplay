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
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillBatchMixedSideChunkDeadlockIntegrationTest {

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

	@Test
	void twoConcurrentChunksWithBuyAndSellSharingAccountsCompleteWithoutDeadlock() throws Exception {
		for (int i = 0; i < REPEAT; i++) {
			runOnceAndAssertNoDeadlock();
		}
	}

	private void runOnceAndAssertNoDeadlock() throws Exception {
		List<Account> accounts = new ArrayList<>();
		for (int i = 0; i < ACCOUNT_COUNT; i++) {
			User user = createUser("mixed-" + i);
			accounts.add(createAccount(user));
		}
		Instrument instrumentX = createCryptoInstrument("MIXX");
		Instrument instrumentY = createCryptoInstrument("MIXY");
		BigDecimal sellQuantity = new BigDecimal("0.05");

		for (Account account : accounts) {
			seedHolding(account, instrumentY, sellQuantity);
		}

		List<Long> chunkXOrderIds = new ArrayList<>();
		List<Long> chunkYOrderIds = new ArrayList<>();
		for (Account account : accounts) {
			chunkXOrderIds.add(createPendingLimitBuy(account, instrumentX));
			chunkYOrderIds.add(createPendingLimitSell(account, instrumentY, sellQuantity));
		}
		List<Long> reversedChunkYOrderIds = new ArrayList<>(chunkYOrderIds);
		Collections.reverse(reversedChunkYOrderIds);

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Consumer<List<Long>> action = orderIds -> limitOrderFillService.fillBatch(orderIds);
			Future<Void> chunkXFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				action.accept(chunkXOrderIds);
				return null;
			});
			Future<Void> chunkYFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				action.accept(reversedChunkYOrderIds);
				return null;
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertCompletesWithoutDeadlock(chunkXFuture);
			assertCompletesWithoutDeadlock(chunkYFuture);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		for (Long orderId : chunkXOrderIds) {
			assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		}
		for (Long orderId : chunkYOrderIds) {
			assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		}
	}

	private void assertCompletesWithoutDeadlock(Future<Void> future) throws InterruptedException, TimeoutException {
		try {
			future.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError("청크 체결이 데드락 또는 예외로 실패했다: " + e.getCause(), e.getCause());
		}
	}

	private void seedHolding(Account account, Instrument instrument, BigDecimal quantity) {
		BigDecimal seedPrice = new BigDecimal("1000000");
		LimitOrderResponse seed = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-mix-seed-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, seedPrice));
		limitOrderFillService.fillBatch(List.of(seed.orderId()));
	}

	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-mix-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		return created.orderId();
	}

	private Long createPendingLimitSell(Account account, Instrument instrument, BigDecimal quantity) {
		BigDecimal limitPrice = new BigDecimal("900000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-mix-sell-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, quantity, limitPrice));
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
