// 054-limit-order-fill-bulk-lock 추가 실측 — 같은 계좌를 지정가 청크(벌크 락)와 시장가 주문이 동시에 건드릴 때
// 시장가 주문 자체의 완료 소요시간이 벌크 락 적용 전후로 얼마나 달라지는지 잰다. 순차 락(적용 전)에서는 원래
// 처리 순서(requestedAt asc, id asc)상 맨 마지막 계좌가 반복문 끝에 가서야 잠기므로 시장가 주문이 락을
// 선점할 여지가 있고, 벌크 락(적용 후)에서는 청크 시작과 동시에 그 계좌도 즉시 잠긴다는 이론을 검증한다.
// CountDownLatch/ExecutorService 구조는 LimitOrderFillBatchMarketOrderDeadlockIntegrationTest를 그대로 따른다.
// CI 게이트가 아니라 수동 실측용이라 성능 임계값을 assert하지 않고 결과를 로그로만 남긴다 — 결과는
// docs/loadtest/limit-order-fill-market-order-contention-benchmark-result.md에 기록한다.
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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillMarketOrderContentionBenchmarkTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);
	// LimitOrderFillBulkLockAccountPoolBenchmarkTest와 같은 계좌풀 규모.
	private static final int ACCOUNT_COUNT = 15;
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
	void measuresMarketOrderCompletionTimeUnderBulkLockContention() throws Exception {
		List<Long> marketOrderMillis = new ArrayList<>(REPEAT);
		List<Long> chunkTotalMillis = new ArrayList<>(REPEAT);
		for (int i = 0; i < REPEAT; i++) {
			long[] result = runOnceAndMeasure(i);
			chunkTotalMillis.add(result[0]);
			marketOrderMillis.add(result[1]);
			log.info(
				"[MARKET-CONTENTION-BENCHMARK] rep={} accountPoolSize={} chunkTotalMillis={} marketOrderMillis={}",
				i, ACCOUNT_COUNT, result[0], result[1]);
		}
		log.info(
			"[MARKET-CONTENTION-BENCHMARK-SUMMARY] medianChunkTotalMillis={} medianMarketOrderMillis={}",
			median(chunkTotalMillis), median(marketOrderMillis));
	}

	// 계좌 ACCOUNT_COUNT개를 만들고 전부 종목 A(지정가 청크)에 PENDING 매수를 건다. 원래 처리 순서
	// (requestedAt asc, id asc, 여기서는 chunkOrderIds에 담긴 생성 순서와 동일)상 맨 마지막 주문의 계좌만 종목
	// B(시장가) 대상으로 삼는다 — 순차 락 방식에서 이 계좌는 반복문 거의 끝에 가서야 잠기고, 벌크 락 방식에서는
	// 청크 시작과 동시에 잠긴다. fillBatch(청크 전체)와 그 계좌를 대상으로 한 시장가 주문 생성을 같은 래치로
	// 동시 출발시켜, 시장가 주문 스레드의 시작~완료 소요시간(nanoTime)을 잰다.
	private long[] runOnceAndMeasure(int rep) throws Exception {
		List<Account> accounts = new ArrayList<>();
		for (int i = 0; i < ACCOUNT_COUNT; i++) {
			User user = createUser("mctn-" + rep + "-" + i);
			accounts.add(createAccount(user));
		}
		Instrument instrumentA = createCryptoInstrument("MCTA");
		Instrument instrumentB = createCryptoInstrument("MCTB");
		BigDecimal marketPrice = new BigDecimal("1000000");
		priceStore.saveTick(instrumentB.getSymbol(), marketPrice, LocalDateTime.now(clock));

		List<Long> chunkOrderIds = new ArrayList<>();
		for (Account account : accounts) {
			chunkOrderIds.add(createPendingLimitBuy(account, instrumentA));
		}
		// chunkOrderIds는 계좌 생성 순서 그대로 쌓였으므로 마지막 원소의 계좌가 원래 처리 순서상 맨 마지막이다.
		Account lastProcessedAccount = accounts.get(ACCOUNT_COUNT - 1);

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Long> bulkLockFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				long startedAt = System.nanoTime();
				limitOrderFillService.fillBatch(chunkOrderIds);
				return (System.nanoTime() - startedAt) / 1_000_000;
			});
			Future<long[]> marketOrderFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				long startedAt = System.nanoTime();
				OrderResponse response = orderService.createOrder(
					lastProcessedAccount.getUser().getId(), "idem-mctn-market-" + UUID.randomUUID(),
					new OrderCreateRequest(
						Market.CRYPTO, instrumentB.getId(), OrderSide.BUY, "MARKET", new BigDecimal("0.01")));
				long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
				assertThat(response.status()).isEqualTo("FILLED");
				return new long[] {elapsedMillis};
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			long chunkElapsedMillis = getWithTimeout(bulkLockFuture);
			long marketOrderElapsedMillis = getWithTimeout(marketOrderFuture)[0];

			for (Long orderId : chunkOrderIds) {
				assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
			}
			return new long[] {chunkElapsedMillis, marketOrderElapsedMillis};
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private <T> T getWithTimeout(Future<T> future) throws InterruptedException, TimeoutException {
		try {
			return future.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError("체결 또는 주문 처리가 데드락 또는 예외로 실패했다: " + e.getCause(), e.getCause());
		}
	}

	private static long median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		sorted.sort(Long::compareTo);
		return sorted.get(sorted.size() / 2);
	}

	// 최소주문금액(5,000)을 넉넉히 넘기면서 계좌 기본 현금(10,000,000) 안에서 지정가·시장가 몫을 함께 예약해도
	// 여유가 있도록 수량·가격을 고정한다(건당 예약 약 10,005원 × 2종목).
	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-mctn-limit-" + UUID.randomUUID(),
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
