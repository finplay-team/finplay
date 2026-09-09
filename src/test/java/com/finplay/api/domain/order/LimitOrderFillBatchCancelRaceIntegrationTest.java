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
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillBatchCancelRaceIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(LimitOrderFillBatchCancelRaceIntegrationTest.class);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);
	private static final int CHUNK_SIZE = 4;
	private static final int REPEAT = 10;

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
	private LimitOrderCancelService limitOrderCancelService;

	@Test
	void fillBatchAndCancelRaceOnSameOrderResolveToSingleConsistentFinalState() throws Exception {
		int fillWinCount = 0;
		int cancelWinCount = 0;
		for (int i = 0; i < REPEAT; i++) {
			RaceOutcome outcome = runOnceAndAssertConsistentFinalState();
			if (outcome == RaceOutcome.FILL_WON) {
				fillWinCount++;
			} else {
				cancelWinCount++;
			}
		}
		log.info(
			"체결·취소 경합 실측 — 총 {}회 중 fill 승리 {}회, cancel 승리 {}회 (매 회 데드락 없이 완료, 최종 상태는 항상 FILLED/CANCELLED 중 하나로 확정)",
			REPEAT, fillWinCount, cancelWinCount);
	}

	private RaceOutcome runOnceAndAssertConsistentFinalState() throws Exception {
		User contestedUser = createUser("race-contested");
		Account contestedAccount = createAccount(contestedUser);
		Instrument instrument = createCryptoInstrument("RACE");
		Long contestedOrderId = createPendingLimitBuy(contestedAccount, instrument);

		List<Long> chunkOrderIds = new ArrayList<>();
		chunkOrderIds.add(contestedOrderId);
		for (int i = 0; i < CHUNK_SIZE - 1; i++) {
			User otherUser = createUser("race-other-" + i);
			Account otherAccount = createAccount(otherUser);
			chunkOrderIds.add(createPendingLimitBuy(otherAccount, instrument));
		}
		List<Long> otherOrderIds = chunkOrderIds.subList(1, chunkOrderIds.size());

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> fillFuture = executor.submit(() -> {
				ready.countDown();
				start.await();
				limitOrderFillService.fillBatch(chunkOrderIds);
				return null;
			});
			Future<Throwable> cancelFuture = executor
				.submit(callableCancel(ready, start, contestedUser.getId(), contestedOrderId));

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertCompletesWithoutException(fillFuture, "fillBatch");
			Throwable cancelFailure = cancelFuture.get(15, TimeUnit.SECONDS);

			for (Long otherOrderId : otherOrderIds) {
				assertThat(orderRepository.findById(otherOrderId).orElseThrow().getStatus())
					.as("경합과 무관한 나머지 청크 주문 orderId=%d", otherOrderId)
					.isEqualTo(OrderStatus.FILLED);
			}

			OrderStatus finalStatus = orderRepository.findById(contestedOrderId).orElseThrow().getStatus();
			if (cancelFailure == null) {
				assertThat(finalStatus).isEqualTo(OrderStatus.CANCELLED);
				return RaceOutcome.CANCEL_WON;
			}
			assertThat(cancelFailure).isInstanceOf(BusinessException.class);
			assertThat(((BusinessException)cancelFailure).getErrorCode()).isEqualTo(ErrorCode.ORDER_ALREADY_FILLED);
			assertThat(finalStatus).isEqualTo(OrderStatus.FILLED);
			return RaceOutcome.FILL_WON;
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<Throwable> callableCancel(CountDownLatch ready, CountDownLatch start, Long userId, Long orderId) {
		return () -> {
			ready.countDown();
			start.await();
			try {
				limitOrderCancelService.cancelOrder(userId, orderId);
				return null;
			} catch (RuntimeException e) {
				return e;
			}
		};
	}

	private void assertCompletesWithoutException(Future<Void> future, String label)
		throws InterruptedException, java.util.concurrent.TimeoutException {
		try {
			future.get(15, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError(label + " 실행이 데드락 또는 예외로 실패했다: " + e.getCause(), e.getCause());
		}
	}

	private enum RaceOutcome {
		FILL_WON, CANCEL_WON
	}

	private Long createPendingLimitBuy(Account account, Instrument instrument) {
		BigDecimal quantity = new BigDecimal("0.01");
		BigDecimal limitPrice = new BigDecimal("1000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-race-" + UUID.randomUUID(),
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
