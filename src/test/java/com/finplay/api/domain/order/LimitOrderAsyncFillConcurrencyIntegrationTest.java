package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderAsyncFillConcurrencyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 12, 0, 0);

	private static final int ORDER_COUNT = 15;

	private static final int CONCURRENT_TICK_COUNT = 4;

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
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("동시 다발 가격 틱 아래에서도 지정가는 요청 순서대로 중복 없이 체결되고 틱 처리 자체는 체결을 기다리지 않는다")
	void concurrentPriceTicksFillEveryPendingOrderExactlyOnceInRequestOrderWithoutBlockingTheFeedThread()
		throws Exception {
		User user = createUser("async-fill");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("ASYNCFIL");

		BigDecimal limitPrice = new BigDecimal("100000");
		BigDecimal quantity = new BigDecimal("0.1");
		List<Long> orderIdsInRequestOrder = new ArrayList<>();
		for (int i = 0; i < ORDER_COUNT; i++) {
			LimitOrderResponse response = limitOrderService.createLimitOrder(
				user.getId(), "idem-async-fill-" + i,
				new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
			orderIdsInRequestOrder.add(response.orderId());
		}

		long tickWallClockMillis = fireConcurrentPriceTickEvents(instrument.getSymbol(), limitPrice);

		assertThat(tickWallClockMillis)
			.as("가격 틱 처리(후보 조회+실행기 제출)가 체결 완료를 기다리지 않고 빠르게 반환됐다")
			.isLessThan(2000L);

		awaitUntil(
			() -> orderIdsInRequestOrder.stream()
				.allMatch(
					orderId -> orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.FILLED),
			Duration.ofSeconds(10), "모든 주문이 제한 시간 안에 체결되지 않았다");

		for (Long orderId : orderIdsInRequestOrder) {
			assertThat(countTradesForOrder(orderId))
				.as("orderId=%d의 Trade가 정확히 1건이어야 한다", orderId)
				.isEqualTo(1L);
		}

		List<Long> tradeIdsInRequestOrder = tradeIdsForOrdersInGivenOrder(orderIdsInRequestOrder);
		assertThat(tradeIdsInRequestOrder).as("요청 순서대로 나열한 Trade id가 오름차순이어야 실제 체결 순서와 일치한다")
			.isSorted();
	}

	private long fireConcurrentPriceTickEvents(String symbol, BigDecimal price) throws Exception {
		CyclicBarrier atTheGate = new CyclicBarrier(CONCURRENT_TICK_COUNT);
		ExecutorService tickExecutor = Executors.newFixedThreadPool(CONCURRENT_TICK_COUNT);
		try {
			long startedAt = System.nanoTime();
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_TICK_COUNT; i++) {
				futures.add(tickExecutor.submit(() -> {
					awaitAtGate(atTheGate);
					eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, NOW, NOW));
				}));
			}
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
			return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		} finally {
			tickExecutor.shutdownNow();
			assertThat(tickExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private List<Long> tradeIdsForOrdersInGivenOrder(List<Long> orderIdsInRequestOrder) {
		List<Long> tradeIds = new ArrayList<>();
		for (Long orderId : orderIdsInRequestOrder) {
			Long tradeId = jdbcTemplate
				.queryForObject("SELECT id FROM trades WHERE order_id = ?", Long.class, orderId);
			tradeIds.add(tradeId);
		}
		return tradeIds;
	}

	private long countTradesForOrder(Long orderId) {
		Long count = jdbcTemplate
			.queryForObject("SELECT COUNT(*) FROM trades WHERE order_id = ?", Long.class, orderId);
		return count == null ? 0L : count;
	}

	private static void awaitAtGate(CyclicBarrier barrier) {
		try {
			barrier.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("배리어 대기 중 인터럽트", e);
		} catch (Exception e) {
			throw new IllegalStateException("배리어에서 모이지 못했다", e);
		}
	}

	private static void awaitUntil(
		java.util.function.BooleanSupplier condition, Duration timeout, String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
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
