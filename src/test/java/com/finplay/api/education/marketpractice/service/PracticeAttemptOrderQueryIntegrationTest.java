// 튜토리얼 attempt 전용 주문 조회(043)의 PENDING→FILLED→재시작 흐름과 기존 샌드박스 제외 회귀를 실제 MySQL로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.order.service.PracticeOrderSettlementService;
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
	// CRYPTO_BASE_PRICE(10000) * factor[0.90, 1.10] 범위(TutorialPriceGenerator) 밖의 값이라 항상 즉시 체결된다.
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

	// 완료 조건: attempt가 없는 사용자·시장 조합은 오류 없이 빈 목록을 반환한다.
	@Test
	void returnsEmptyListWhenAttemptDoesNotExist() {
		User user = user("no-attempt");

		assertThat(orderQueryService.getCurrentRunOrders(user.getId(), Market.CRYPTO)).isEmpty();
	}

	// 완료 조건 전체를 관통하는 시나리오: 지정가 매수 생성 → PENDING 노출(+ 기존 조회 회귀 없음) → tick 체결 →
	// FILLED 노출(+ 기존 조회 회귀 없음) → 재시작 → 이전 run 주문 제외(새 run은 빈 목록).
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
		// TUTORIAL-ORDER-004 / SANDBOX-EXCL(033) 회귀: 이 시점에도 기존 두 조회는 샘플 종목 주문을 노출하지 않는다.
		assertThat(orderService.getMyOrders(
			fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100).content()).isEmpty();
		assertThat(orderService.getMyPendingOrders(
			fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100).content()).isEmpty();

		practiceOrderSettlementService.settleCurrentRun(
			fixture.attempt().getId(), fixture.attempt().getRunNumber(), NOW.plusMinutes(1));

		List<OrderListItemResponse> filled = orderQueryService.getCurrentRunOrders(
			fixture.user().getId(), Market.CRYPTO);
		assertThat(filled).singleElement().satisfies(item -> {
			assertThat(item.orderId()).isEqualTo(created.orderId());
			assertThat(item.status()).isEqualTo("FILLED");
		});
		assertThat(orderService.getMyOrders(
			fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100).content()).isEmpty();
		assertThat(orderService.getMyPendingOrders(
			fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100).content()).isEmpty();

		restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(orderQueryService.getCurrentRunOrders(fixture.user().getId(), Market.CRYPTO)).isEmpty();
		assertThat(attemptRepository.findById(fixture.attempt().getId()).orElseThrow().getRunNumber())
			.isEqualTo(2L);
	}

	private Fixture selectedFixture(String scenario, Market market) {
		User user = user(scenario);
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.valueOf(market.name()), NOW));
		accountIds.add(account.getId());
		Instrument instrument = Instrument.create(
			market, "T" + UUID.randomUUID().toString().substring(0, 8), scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), market, NOW.minusHours(1));
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
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
