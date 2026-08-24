// 사용자 취소(ExitPlanCancelService)와 가격 트리거 체결(ExitPlanFillService)이 같은 PENDING exit plan에
// 동시에 경합할 때 정확히 한쪽만 성공하고 holding.reservedQuantity가 이중 반환·이중 소비 없이 일관되는지
// 검증하는 통합 테스트다(021 plan.md "테스트 계획" — 취소 ↔ 트리거 경합, `015-limit-order`의
// LimitOrderConcurrencyIntegrationTest "취소-대-체결" 동시성 테스트 패턴을 그대로 재사용한다). 두 서비스 모두
// holding을 plan보다 먼저 잠그므로(취소: holding→plan, 체결: account→holding→plan) 서로 반대 순서로 잠그는
// 흐름이 없어 데드락 없이 완료돼야 한다.
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

	// 시나리오: 같은 PENDING exit plan에 사용자 취소(holding→plan 잠금)와 가격 트리거 체결(account→holding→plan
	// 잠금)을 거의 동시에 호출한다. 어느 쪽이 이길지는 스케줄링에 좌우되므로 두 결과 분기를 모두 검증한다 — 이긴
	// 쪽만 holding.reservedQuantity를 정확히 한 번 소비 또는 반환하고, 진 쪽은 예외(취소) 또는 조용한 no-op(체결)
	// 이어야 한다(021 plan.md "트리거·취소·잠금 순서" — "정확히 한 번 규칙").
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

		// 트리거 판정에 쓸 현재가는 익절가 이상 — fillIfPending은 이 값으로 취익 조건을 재판정한다.
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
			// 체결이 이겼다 — 취소는 EXIT_PLAN_NOT_PENDING으로 거부되고, 예약은 정확히 한 번만 실제 매도로 소비된다.
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.EXIT_PLAN_NOT_PENDING));
			assertThat(finalPlan.getTriggeredOrder()).isNotNull();
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(reservedQuantity);
		} else {
			// 취소가 이겼다 — 체결은 예외 없이 조용히 no-op해야 하고, 예약은 매도 없이 그대로 반환된다.
			assertThat(finalPlan.getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(finalPlan.getTriggeredOrder()).isNull();
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		}
	}

	// PracticeIntentionConcurrencyIntegrationTest·LimitOrderConcurrencyIntegrationTest와 동일한 ready/start
	// CountDownLatch 관례를 재사용한다 — 두 액션을 준비 완료(ready) 후 동시에 출발(start)시켜 실제 락 경합을
	// 재현하고, 어느 한쪽이라도 예외(데드락 등)를 던지면 그대로 테스트 실패로 전파한다.
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
