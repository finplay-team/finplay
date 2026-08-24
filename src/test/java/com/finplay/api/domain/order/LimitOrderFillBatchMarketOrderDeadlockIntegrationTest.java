// 054-limit-order-fill-bulk-lock 위험 요소 1 확장 검증 — 지정가 청크의 벌크 FOR UPDATE(계좌 ID 오름차순)가
// 진행되는 도중에, 그 계좌 중 일부를 시장가 주문(OrderExecutionService, spec.md 범위 제외라 여전히 개별
// SELECT ... FOR UPDATE)이 동시에 잠그려 하면 CannotAcquireLockException(데드락)이 나는지 확인한다. 시장가도
// 지정가 청크와 같은 종목을 사서 계좌뿐 아니라 holding 행까지 겹치게 한다(PR #545 리뷰 권장사항 6번) — 종목을
// 다르게 두면 겹치는 자원이 계좌 하나뿐이라 진짜 데드락(순환 대기)이 성립할 수 없고 lock-wait 지연으로만
// 실패할 수 있는 약한 테스트가 된다.
// LimitOrderFillBatchCrossInstrumentChunkDeadlockIntegrationTest·LimitOrderFillAccountLockContentionIntegrationTest의
// CountDownLatch/ExecutorService/REPEAT=5 패턴을 그대로 따른다.
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
	// 계좌 6개 중 뒤쪽 절반(인덱스 3~5)만 시장가 주문과 겹치게 한다 — 벌크 락(계좌 ID 오름차순 SELECT ... FOR
	// UPDATE)이 그 계좌들에 도달하기 전에 시장가 주문이 먼저 개별 락을 잡을 여유를 준다.
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

	// 계좌 ACCOUNT_COUNT개를 만들고 전부 종목 A(지정가 청크)에 PENDING 매수를 걸어둔다. 그중 뒤쪽 절반은 같은
	// 종목 A에 시장가 매수도 낸다 — 계좌뿐 아니라 holding 행까지 겹쳐야 진짜 경합이다. fillBatch(종목 A 청크
	// 전체, 계좌 ID 오름차순 벌크 락)와 시장가 주문(겹치는 계좌만, 각자 개별 SELECT ... FOR UPDATE)을 같은
	// 래치로 동시 출발시켜, 벌크 락 진행 도중 개별 락이 끼어들어도 CannotAcquireLockException 없이 둘 다
	// 끝나는지 확인한다.
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

	// 최소주문금액(5,000)을 넉넉히 넘기면서 계좌 기본 현금(10,000,000) 안에서 지정가·시장가 몫을 함께 예약해도
	// 여유가 있도록 수량·가격을 고정한다(같은 종목에 건당 예약 약 10,005원 × 2건).
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
