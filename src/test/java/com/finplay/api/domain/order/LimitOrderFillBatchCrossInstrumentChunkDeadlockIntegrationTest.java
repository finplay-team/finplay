// 054-limit-order-fill-bulk-lock 위험 요소 1 — 서로 다른 종목(=서로 다른 파티션 워커)의 청크 두 개가 겹치는
// 계좌 집합을 동시에 벌크 락으로 잠글 때, 계좌 ID 오름차순 고정이 없으면 ABBA 데드락이 날 수 있다. fillBatch가
// 두 청크 모두 계좌 ID 오름차순으로 잠그는지 실제 동시 실행으로 검증한다. LimitOrderFillAccountLockContentionIntegrationTest
// 의 ready/start 래치 패턴을 참고했다.
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
class LimitOrderFillBatchCrossInstrumentChunkDeadlockIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);
	// 겹치는 계좌 수 — 두 청크가 같은 계좌 전부를 공유하게 해 계좌 락 경합을 최대화한다.
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
	void twoConcurrentChunksOfDifferentInstrumentsSharingAccountsCompleteWithoutDeadlock() throws Exception {
		for (int i = 0; i < REPEAT; i++) {
			runOnceAndAssertNoDeadlock();
		}
	}

	// 계좌 ACCOUNT_COUNT개를 만들고, 각 계좌마다 서로 다른 종목 X·Y에 PENDING 지정가 매수를 하나씩 걸어둔다.
	// 두 청크(종목 X 전체, 종목 Y 전체)를 서로 반대 순서(내림차순 vs 오름차순)로 넘겨 실제 락 획득 순서가
	// fillBatch 내부 정렬에만 의존하도록 만든다 — 호출부 순서에 우연히 맞물려 데드락이 안 나는 거짓 양성을 막는다.
	private void runOnceAndAssertNoDeadlock() throws Exception {
		List<Account> accounts = new ArrayList<>();
		for (int i = 0; i < ACCOUNT_COUNT; i++) {
			User user = createUser("cross-" + i);
			accounts.add(createAccount(user));
		}
		Instrument instrumentX = createCryptoInstrument("CROSSX");
		Instrument instrumentY = createCryptoInstrument("CROSSY");

		List<Long> chunkXOrderIds = new ArrayList<>();
		List<Long> chunkYOrderIds = new ArrayList<>();
		for (Account account : accounts) {
			chunkXOrderIds.add(createPendingLimitBuy(account, instrumentX));
			chunkYOrderIds.add(createPendingLimitBuy(account, instrumentY));
		}
		// 계좌 ID 오름차순으로 만들어진 accounts 리스트를 그대로 쓴 X와, 반대로 뒤집은 순서로 청크를 넘기는 Y —
		// fillBatch 호출 인자 순서와 무관하게 내부에서 계좌 ID 오름차순으로 잠가야 데드락이 나지 않는다.
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

	// 최소주문금액(5,000)을 넉넉히 넘기면서 계좌 기본 현금(10,000,000) 안에서 두 종목 몫을 함께 예약해도
	// 여유가 있도록 수량·가격을 고정한다(건당 예약 약 10,005원 × 2종목).
	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-cross-" + UUID.randomUUID(),
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
