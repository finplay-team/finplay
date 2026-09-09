package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
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
class TutorialCashIsolationFullCycleIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final long INITIAL_TUTORIAL_CASH = 10_000_000L;
	private static final int CYCLES = 3;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeAttemptRestartService practiceAttemptRestartService;
	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void tearDown() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM practice_market_observations WHERE user_id = ?", userId);
			jdbcTemplate.update(
				"DELETE FROM practice_risk_snapshots WHERE attempt_id IN "
					+ "(SELECT id FROM practice_attempts WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM trade_allocations WHERE holding_lot_id IN "
				+ "(SELECT id FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)))", userId);
			jdbcTemplate.update("DELETE FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN (SELECT id FROM accounts WHERE user_id = ?))",
				userId);
			jdbcTemplate.update("DELETE FROM exit_plan_conditions WHERE exit_plan_id IN "
				+ "(SELECT id FROM exit_plans WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM exit_plans WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM trades WHERE order_id IN "
				+ "(SELECT id FROM orders WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM practice_attempts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		instrumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", id));
		userIds.clear();
		instrumentIds.clear();
	}

	@Test
	void repeatedBuyObserveSellRestartCyclesNeverChangeRealAccountCashBalance() {
		User user = createUser("issue450-repro");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("issue450-repro");

		long realCashBaseline = account.getCashBalance();
		long realReservedBaseline = account.getReservedCash();
		long realRealizedPnlBaseline = account.getRealizedPnl();
		assertThat(realCashBaseline).isEqualTo(10_000_000L);

		PracticeAttemptResponse entry = practiceAttemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		assertThat(entry.tutorialCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
		assertRealAccountUnchanged(account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

		for (int cycle = 1; cycle <= CYCLES; cycle++) {
			practiceAttemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			orderService.createOrder(user.getId(), "issue450-buy-" + cycle + "-" + UUID.randomUUID(),
				new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET",
					new BigDecimal("2")));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);
			TutorialAccount tutorialAfterBuy = tutorialAccount(user.getId());
			assertThat(tutorialAfterBuy.getCashBalance()).isLessThan(INITIAL_TUTORIAL_CASH);

			Holding holding = holdingRepository
				.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
				.orElseThrow();
			practiceHoldingObservationService.createObservation(
				user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			orderService.createOrder(user.getId(), "issue450-sell-" + cycle + "-" + UUID.randomUUID(),
				new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET",
					new BigDecimal("1")));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			PracticeAttemptResponse restartResponse = practiceAttemptRestartService.restart(
				user.getId(), Market.CRYPTO);
			assertThat(restartResponse.tutorialCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(restartResponse.tutorialAvailableCash()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(restartResponse.tutorialRealizedPnl()).isZero();
			assertThat(restartResponse.runNumber()).isEqualTo(cycle + 1L);

			TutorialAccount tutorialAfterRestart = tutorialAccount(user.getId());
			assertThat(tutorialAfterRestart.getCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(tutorialAfterRestart.getReservedCash()).isZero();
			assertThat(tutorialAfterRestart.getRealizedPnl()).isZero();

			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);
		}

		Account finalAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(finalAccount.getCashBalance()).isEqualTo(realCashBaseline);
		assertThat(finalAccount.getReservedCash()).isEqualTo(realReservedBaseline);
		assertThat(finalAccount.getRealizedPnl()).isEqualTo(realRealizedPnlBaseline);
	}

	private void assertRealAccountUnchanged(
		Long accountId, long expectedCash, long expectedReserved, long expectedRealizedPnl) {
		Account current = accountRepository.findById(accountId).orElseThrow();
		assertThat(current.getCashBalance()).isEqualTo(expectedCash);
		assertThat(current.getReservedCash()).isEqualTo(expectedReserved);
		assertThat(current.getRealizedPnl()).isEqualTo(expectedRealizedPnl);
	}

	private TutorialAccount tutorialAccount(Long userId) {
		return tutorialAccountRepository
			.findByUserIdAndMarket(userId, Market.CRYPTO)
			.orElseThrow();
	}

	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
		userIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + shortRandom(), scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		return instrument;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + shortRandom() + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + shortRandom();
	}

	private static String shortRandom() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
