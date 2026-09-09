package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeOrderBasicsFullJourneyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 9, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	private static final BigDecimal OUT_OF_RANGE_SELL_PRICE = new BigDecimal("130000");
	private static final BigDecimal IN_RANGE_SELL_PRICE = new BigDecimal("108000");
	private static final int SECONDS_PER_TICK = 30;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private OrderService orderService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private LimitOrderCancelService limitOrderCancelService;
	@Autowired
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void unfilledLimitOrderCanBeCancelledAndReplacedInsideTheGuideRangeUntilItFills() {
		Fixture fixture = orderBasicsRun("ob-full-journey");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();

		clock.set(BASE_NOW.plusSeconds(3));
		buy(fixture);

		clock.set(BASE_NOW.plusSeconds(4));
		LimitOrderResponse outOfRangeOrder = sellLimit(fixture, OUT_OF_RANGE_SELL_PRICE);
		assertThat(outOfRangeOrder.status()).isEqualTo("PENDING");

		tick(fixture, BASE_NOW.plusSeconds(5));
		tick(fixture, BASE_NOW.plusSeconds(5 + SECONDS_PER_TICK));
		tick(fixture, BASE_NOW.plusSeconds(5 + (long)SECONDS_PER_TICK * 2));
		assertThat(orderStatus(outOfRangeOrder.orderId())).isEqualTo(OrderStatus.PENDING);

		limitOrderCancelService.cancelOrder(fixture.userId(), outOfRangeOrder.orderId());
		assertThat(orderStatus(outOfRangeOrder.orderId())).isEqualTo(OrderStatus.CANCELLED);

		LimitOrderResponse inRangeOrder = sellLimit(fixture, IN_RANGE_SELL_PRICE);
		assertThat(inRangeOrder.status()).isEqualTo("PENDING");

		long tickAt = 5 + (long)SECONDS_PER_TICK * 2;
		for (int round = 1; round <= 4 && orderStatus(inRangeOrder.orderId()) == OrderStatus.PENDING; round++) {
			tickAt += SECONDS_PER_TICK;
			tick(fixture, BASE_NOW.plusSeconds(tickAt));
		}

		assertThat(orderStatus(inRangeOrder.orderId())).isEqualTo(OrderStatus.FILLED);
		Order filled = orderRepository.findById(inRangeOrder.orderId()).orElseThrow();
		assertThat(filled.getSide()).isEqualTo(OrderSide.SELL);
	}

	private void tick(Fixture fixture, LocalDateTime at) {
		clock.set(at);
		chartService.tick(fixture.userId(), Market.CRYPTO);
	}

	private boolean marketRoundTripCompleted(Fixture fixture) {
		return stageProgressCalculationService
			.calculate(attemptRepository.findById(fixture.attemptId()).orElseThrow())
			.marketBuySellCompleted();
	}

	private OrderStatus orderStatus(Long orderId) {
		return orderRepository.findById(orderId).orElseThrow().getStatus();
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-journey-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-journey-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private LimitOrderResponse sellLimit(Fixture fixture, BigDecimal limitPrice) {
		return limitOrderService.createLimitOrder(fixture.userId(), "ob-journey-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, QUANTITY, limitPrice));
	}

	private Fixture orderBasicsRun(String scenario) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + suffix, scenario, BigDecimal.ONE, 0L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
