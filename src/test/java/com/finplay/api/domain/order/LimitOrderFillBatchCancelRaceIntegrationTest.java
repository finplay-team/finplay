// 054-limit-order-fill-bulk-lock 추가 검증 — fillBatch(청크 벌크 락)와 LimitOrderCancelService(단건 락)가
// 같은 PENDING 주문을 동시에 다툴 때 데드락 없이 완료되고, 최종 상태가 FILLED/CANCELLED 중 하나로만 확정되는지 검증한다.
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
	// fillBatch 청크 안에 경합 대상 주문 외에 다른 계좌 주문도 섞어 벌크 락(order→account→holding) 경로를 그대로 탄다.
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

	// PENDING 지정가 매수 주문 하나(경합 대상)와, 같은 종목의 다른 계좌 주문 (CHUNK_SIZE - 1)건을 청크로 묶어
	// fillBatch를 호출하는 스레드와, 경합 대상 주문만 취소하는 스레드를 ready/start 래치로 동시에 출발시킨다.
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

			// fillBatch는 경합 대상 주문이 취소로 먼저 상태가 바뀌어도 그 건만 skip하고 나머지는 정상 체결해야
			// 한다 — 데드락은 물론 어떤 예외도 던지지 않아야 한다.
			assertCompletesWithoutException(fillFuture, "fillBatch");
			Throwable cancelFailure = cancelFuture.get(15, TimeUnit.SECONDS);

			for (Long otherOrderId : otherOrderIds) {
				assertThat(orderRepository.findById(otherOrderId).orElseThrow().getStatus())
					.as("경합과 무관한 나머지 청크 주문 orderId=%d", otherOrderId)
					.isEqualTo(OrderStatus.FILLED);
			}

			OrderStatus finalStatus = orderRepository.findById(contestedOrderId).orElseThrow().getStatus();
			if (cancelFailure == null) {
				// cancel이 이겼다 — order.cancel() 후 fillBatch는 PENDING이 아님을 보고 skip해야 한다.
				assertThat(finalStatus).isEqualTo(OrderStatus.CANCELLED);
				return RaceOutcome.CANCEL_WON;
			}
			// fill이 이겼다 — cancel은 데드락이 아니라 "이미 체결됨" 비즈니스 예외로만 실패해야 한다.
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

	// 최소주문금액(5,000)을 넉넉히 넘기면서 계좌 기본 현금(10,000,000) 안에서 여유 있게 예약되도록 수량·가격을 고정한다.
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
