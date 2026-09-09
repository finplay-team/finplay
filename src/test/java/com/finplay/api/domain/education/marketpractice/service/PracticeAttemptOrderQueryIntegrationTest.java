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
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PracticeAttemptOrderQueryIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2036, 8, 18, 10, 0);
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("20000");
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private PracticeOrderSettlementService practiceOrderSettlementService;
	@Autowired
	private PracticeAttemptOrderQueryService orderQueryService;
	@Autowired
	private PracticeAttemptRestartService restartService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> accountIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void cleanUp() {
		for (Long userId : userIds) {
			jdbcTemplate.update(
				"DELETE FROM practice_risk_snapshots WHERE attempt_id IN "
					+ "(SELECT id FROM practice_attempts WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
		}
		for (Long accountId : accountIds) {
			jdbcTemplate.update(
				"DELETE FROM trade_allocations WHERE holding_lot_id IN "
					+ "(SELECT id FROM holding_lots WHERE holding_id IN "
					+ "(SELECT id FROM holdings WHERE account_id = ?))",
				accountId);
			jdbcTemplate.update(
				"DELETE FROM holding_lots WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
				accountId);
			jdbcTemplate.update("DELETE FROM trades WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM orders WHERE account_id = ?", accountId);
			jdbcTemplate.update(
				"DELETE FROM exit_plan_conditions WHERE exit_plan_id IN "
					+ "(SELECT id FROM exit_plans WHERE holding_id IN "
					+ "(SELECT id FROM holdings WHERE account_id = ?))",
				accountId);
			jdbcTemplate.update(
				"DELETE FROM exit_plans WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
				accountId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id = ?", accountId);
		}
		userIds.forEach(userId -> jdbcTemplate.update("DELETE FROM practice_attempts WHERE user_id = ?", userId));
		accountIds.forEach(accountId -> jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId));
		instrumentIds
			.forEach(instrumentId -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrumentId));
		userIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
		userIds.clear();
		accountIds.clear();
		instrumentIds.clear();
	}

	@Test
	void returnsEmptyListWhenAttemptDoesNotExist() {
		User user = user("no-attempt");

		assertThat(orderQueryService.getCurrentRunOrders(user.getId(), Market.CRYPTO)).isEmpty();
	}

	@Test
	void limitBuyOrderIsVisibleThroughPendingAndFilledThenExcludedAfterRestartWithoutSandboxRegression() {
		Fixture fixture = selectedFixture("order-query", Market.CRYPTO);

		LimitOrderResponse created = limitOrderService.createLimitOrder(
			fixture.user().getId(), "order-query-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrument().getId(), OrderSide.BUY, QUANTITY,
				LIMIT_PRICE));

		List<OrderListItemResponse> pending = orderQueryService.getCurrentRunOrders(
			fixture.user().getId(), Market.CRYPTO);
		assertThat(pending).singleElement().satisfies(item -> {
			assertThat(item.orderId()).isEqualTo(created.orderId());
			assertThat(item.status()).isEqualTo("PENDING");
			assertThat(item.practiceAttemptId()).isEqualTo(fixture.attempt().getId());
			assertThat(item.practiceAttemptRunNumber()).isEqualTo(1L);
		});
		assertThat(orderService.getMyOrders(
			fixture.user().getId(), Market.CRYPTO, null, 100).content())
			.isEmpty();
		assertThat(orderService.getMyPendingOrders(
			fixture.user().getId(), Market.CRYPTO, null, 100).content())
			.isEmpty();

		practiceOrderSettlementService.settleCurrentRun(
			fixture.attempt().getId(), fixture.attempt().getRunNumber(), NOW.plusMinutes(1), null);

		List<OrderListItemResponse> filled = orderQueryService.getCurrentRunOrders(
			fixture.user().getId(), Market.CRYPTO);
		assertThat(filled).singleElement().satisfies(item -> {
			assertThat(item.orderId()).isEqualTo(created.orderId());
			assertThat(item.status()).isEqualTo("FILLED");
		});
		assertThat(orderService.getMyOrders(
			fixture.user().getId(), Market.CRYPTO, null, 100).content())
			.isEmpty();
		assertThat(orderService.getMyPendingOrders(
			fixture.user().getId(), Market.CRYPTO, null, 100).content())
			.isEmpty();

		restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(orderQueryService.getCurrentRunOrders(fixture.user().getId(), Market.CRYPTO)).isEmpty();
		assertThat(attemptRepository.findById(fixture.attempt().getId()).orElseThrow().getRunNumber())
			.isEqualTo(2L);
	}

	private Fixture selectedFixture(String scenario, Market market) {
		User user = user(scenario);
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.valueOf(market.name()), NOW));
		accountIds.add(account.getId());
		Instrument instrument = Instrument.create(
			market, "T" + UUID.randomUUID().toString().substring(0, 8), scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), market, NOW.minusHours(1));
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(10));
		attemptRepository.saveAndFlush(attempt);
		return new Fixture(user, account, instrument, attempt);
	}

	private User user(String scenario) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, NOW));
		userIds.add(user.getId());
		return user;
	}

	private record Fixture(User user, Account account, Instrument instrument, PracticeAttempt attempt) {
	}
}
