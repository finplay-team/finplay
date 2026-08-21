// tick 진행이 같은 세션의 교육 지정가 주문만 체결·취소하고, 일반 주문·타 세션·타 사용자 주문은
// 건드리지 않음을 실제 MySQL로 검증하는 통합 테스트다 (030 COIN-PRICE-RUNTIME-007/008/011).
package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCreationService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeLimitOrderTickIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);
	// 30,000원은 BTC 세션의 어떤 tick 가격보다도 항상 높다(±1%/tick, startPrice=10,000) — tick 1에서 즉시 체결된다.
	private static final BigDecimal ALWAYS_FILLS_LIMIT_PRICE = new BigDecimal("30000");
	// 4,000원은 ETH 세션의 하한(startPrice*0.5=5,000)보다 낮아 100틱 동안 절대 체결되지 않는다.
	private static final BigDecimal NEVER_FILLS_LIMIT_PRICE = new BigDecimal("4000");
	private static final BigDecimal START_PRICE = new BigDecimal("10000.00000000");

	@Autowired
	private PracticeLimitOrderService practiceLimitOrderService;
	@Autowired
	private PracticePriceTickService practicePriceTickService;
	@Autowired
	private PracticePriceSessionRepository practicePriceSessionRepository;
	@Autowired
	private LimitOrderCreationService limitOrderCreationService;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long userAId;
	private Long userCId;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@AfterEach
	void cleanUp() {
		List<Long> userIds = List.of(userAId, userCId);
		jdbcTemplate.update(
			"DELETE FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN (SELECT id FROM accounts WHERE user_id IN (?, ?)))",
			userAId, userCId);
		jdbcTemplate.update(
			"DELETE FROM holdings WHERE account_id IN (SELECT id FROM accounts WHERE user_id IN (?, ?))",
			userAId, userCId);
		jdbcTemplate.update(
			"DELETE FROM trades WHERE account_id IN (SELECT id FROM accounts WHERE user_id IN (?, ?))",
			userAId, userCId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id IN (?, ?)", userAId, userCId);
		jdbcTemplate.update("DELETE FROM practice_price_sessions WHERE user_id IN (?, ?)", userAId, userCId);
		jdbcTemplate.update("DELETE FROM accounts WHERE user_id IN (?, ?)", userAId, userCId);
		jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userCId);
	}

	// 시나리오: 같은 사용자가 BTC 세션(즉시 체결 지정가)과 ETH 세션(끝까지 미체결 지정가)을 각각 tick 1..99까지
	// 진행한다. 함께 존재하는 일반 지정가 주문과 다른 사용자의 BTC 세션 주문은 어느 tick에서도 건드려지지 않아야
	// 하고, ETH 세션은 tick 99에서 "체결 판정 → 잔여 취소·예약 반환 → 세션 COMPLETED 전이" 순서를 지켜야 한다.
	@Test
	void tickAdvancesFillOnlySameSessionOrdersAndCancelUnfilledOrdersExactlyAtLastTick() {
		User userA = createUser("tick-e2e-a");
		User userC = createUser("tick-e2e-c");
		userAId = userA.getId();
		userCId = userC.getId();
		Account accountA = accountRepository.saveAndFlush(
			Account.create(userA, Market.CRYPTO, NOW));
		accountRepository.saveAndFlush(
			Account.create(userC, Market.CRYPTO, NOW));
		Instrument btc = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "BTC").orElseThrow();
		Instrument eth = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "ETH").orElseThrow();

		Long sessionAId = createSession(userA.getId(), btc.getId(), 111L).getId();
		Long sessionBId = createSession(userA.getId(), eth.getId(), 222L).getId();
		Long sessionCId = createSession(userC.getId(), btc.getId(), 333L).getId();

		LimitOrderResponse orderA = practiceLimitOrderService.createOrder(userA.getId(),
			new PracticeLimitOrderCreateRequest(sessionAId, btc.getId(), BigDecimal.ONE, ALWAYS_FILLS_LIMIT_PRICE));
		LimitOrderResponse orderB = practiceLimitOrderService.createOrder(userA.getId(),
			new PracticeLimitOrderCreateRequest(
				sessionBId, eth.getId(), new BigDecimal("2"), NEVER_FILLS_LIMIT_PRICE));
		LimitOrderResponse orderC = practiceLimitOrderService.createOrder(userC.getId(),
			new PracticeLimitOrderCreateRequest(sessionCId, btc.getId(), BigDecimal.ONE, ALWAYS_FILLS_LIMIT_PRICE));
		LimitOrderResponse normalOrder = limitOrderCreationService.execute(
			userA.getId(), "normal-idem-" + UUID.randomUUID(), "n".repeat(64),
			new LimitOrderCreateRequest(
				Market.CRYPTO, btc.getId(), OrderSide.BUY, BigDecimal.ONE, ALWAYS_FILLS_LIMIT_PRICE));

		for (int tick = 1; tick <= 99; tick++) {
			practicePriceTickService.advanceTick(userA.getId(), sessionAId, tick);
			practicePriceTickService.advanceTick(userA.getId(), sessionBId, tick);
		}

		assertThat(orderRepository.findById(orderA.orderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.FILLED);
		assertThat(orderRepository.findById(orderB.orderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(orderRepository.findById(normalOrder.orderId()).orElseThrow().getStatus())
			.as("일반 주문은 practice tick 이벤트로 체결되지 않는다(COIN-PRICE-RUNTIME-011)")
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(orderC.orderId()).orElseThrow().getStatus())
			.as("다른 사용자의 세션 주문은 sessionA/B의 tick으로 건드려지지 않는다")
			.isEqualTo(OrderStatus.PENDING);

		PracticePriceSession sessionA = practicePriceSessionRepository.findById(sessionAId).orElseThrow();
		PracticePriceSession sessionB = practicePriceSessionRepository.findById(sessionBId).orElseThrow();
		PracticePriceSession sessionC = practicePriceSessionRepository.findById(sessionCId).orElseThrow();
		assertThat(sessionA.getStatus()).isEqualTo(PracticePriceSessionStatus.COMPLETED);
		assertThat(sessionB.getStatus()).isEqualTo(PracticePriceSessionStatus.COMPLETED);
		assertThat(sessionC.getCurrentTick())
			.as("다른 세션은 다른 세션의 next-tick 호출로 진행되지 않는다")
			.isZero();
		assertThat(sessionC.getStatus()).isEqualTo(PracticePriceSessionStatus.ACTIVE);

		List<Holding> holdingsA = holdingRepository.findAllByAccountIdAndIsActiveTrue(accountA.getId());
		assertThat(holdingsA)
			.filteredOn(h -> h.getInstrument().getId().equals(btc.getId()))
			.singleElement()
			.satisfies(h -> assertThat(h.getQuantity()).isEqualByComparingTo(BigDecimal.ONE));

		Account accountAfter = accountRepository.findById(accountA.getId()).orElseThrow();
		// orderA 체결(amount=30,000, fee=15) 확정 + orderB 취소(amount=8,000, fee=4) 예약 반환 후,
		// normalOrder의 예약(amount=30,000, fee=15)만 reservedCash로 남는다.
		assertThat(accountAfter.getReservedCash()).isEqualTo(30_015L);
		assertThat(accountAfter.getCashBalance()).isEqualTo(10_000_000L - 30_015L);
	}

	private PracticePriceSession createSession(Long userId, Long instrumentId, long seed) {
		return practicePriceSessionRepository.saveAndFlush(
			PracticePriceSession.create(
				userId, instrumentId, seed, (short)PracticePriceGeneratorV1.VERSION, START_PRICE, NOW));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "hash", uniqueNickname(scenario), NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
