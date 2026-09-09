package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PracticeAttemptRestartIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2036, 8, 14, 15, 0);
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("1000000");

	@Autowired
	private PracticeAttemptRestartService restartService;
	@Autowired
	private PracticeAttemptChartService chartService;
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
	private TradeRepository tradeRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private HoldingLotRepository holdingLotRepository;
	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> accountIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void cleanUp() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
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

	@Test
	void restartCancelsOnlyCurrentRunOrdersAndKeepsOtherUserMarketRunAndGeneralOrders() {
		Fixture fixture = selectedFixture("isolation", Market.CRYPTO);
		Holding holding = holding(fixture, new BigDecimal("3"));
		reservedTutorialAccount(fixture.user(), Market.CRYPTO, 100_050L);
		fixture.account().reserveCash(500_250L);
		accountRepository.saveAndFlush(fixture.account());
		holding.reserveQuantity(new BigDecimal("2"));
		holdingRepository.saveAndFlush(holding);

		Order currentBuy = attributedPending(fixture, OrderSide.BUY, "0.1", 1L, "current-buy");
		Order currentSell = attributedPending(fixture, OrderSide.SELL, "1", 1L, "current-sell");
		Order otherRun = attributedPending(fixture, OrderSide.BUY, "0.2", 2L, "other-run");
		Order general = ordinaryPending(fixture, "0.3", "general");
		orderRepository.saveAllAndFlush(List.of(currentBuy, currentSell, otherRun, general));

		Fixture otherUser = selectedFixture("other-user", Market.CRYPTO);
		otherUser.account().reserveCash(100_050L);
		accountRepository.saveAndFlush(otherUser.account());
		Order otherUserOrder = orderRepository.saveAndFlush(
			attributedPending(otherUser, OrderSide.BUY, "0.1", 1L, "other-user"));

		Fixture otherMarket = selectedFixtureForExistingUser(fixture.user(), "other-market", Market.STOCK);
		otherMarket.account().reserveCash(100_015L);
		accountRepository.saveAndFlush(otherMarket.account());
		Order otherMarketOrder = orderRepository.saveAndFlush(
			attributedPending(otherMarket, OrderSide.BUY, "0.1", 1L, "other-market"));

		PracticeAttemptResponse response = restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(orderRepository.findById(currentBuy.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(orderRepository.findById(currentSell.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(orderRepository.findById(otherRun.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(general.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(otherUserOrder.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(otherMarketOrder.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(fixture.account().getId()).orElseThrow().getReservedCash())
			.isEqualTo(500_250L);
		assertThat(holdingRepository.findById(holding.getId()).orElseThrow().getReservedQuantity())
			.isEqualByComparingTo("1");
	}

	@Test
	void restartRollsBackPendingCancellationAndReservationReturnWhenHoldingMismatchExists() {
		Fixture fixture = selectedFixture("rollback", Market.CRYPTO);
		Holding holding = holding(fixture, BigDecimal.ONE);
		holdingRepository.saveAndFlush(holding);
		TutorialAccount tutorialAccount = reservedTutorialAccount(fixture.user(), Market.CRYPTO, 100_050L);
		Long tutorialAccountId = tutorialAccount.getId();
		Order pending = orderRepository.saveAndFlush(
			attributedPending(fixture, OrderSide.BUY, "0.1", 1L, "rollback-pending"));
		Order filledBuy = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, OrderType.MARKET,
			new BigDecimal("1.5"), fixture.attempt().getId(), 1L, "rollback-filled", "f".repeat(64), NOW));
		tradeRepository.saveAndFlush(Trade.of(
			filledBuy, fixture.account(), fixture.instrument(), null, OrderSide.BUY, BigDecimal.valueOf(90_000),
			new BigDecimal("1.5"), 135_000L, 67L, null, NOW, NOW));

		assertThatThrownBy(() -> restartService.restart(fixture.user().getId(), Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		assertThat(attemptRepository.findById(fixture.attempt().getId()).orElseThrow().getRunNumber()).isEqualTo(1L);
		assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(fixture.account().getId()).orElseThrow().getReservedCash()).isZero();
		assertThat(tutorialAccountRepository.findById(tutorialAccountId).orElseThrow().getReservedCash())
			.isEqualTo(100_050L);
		assertThat(orderRepository.count()).isGreaterThanOrEqualTo(2L);
	}

	@Test
	void restartWithExactAvailableHoldingCreatesCompensatingAuditOrderTradeAndAllocation() {
		Fixture fixture = selectedFixture("compensation", Market.CRYPTO);
		BigDecimal quantity = new BigDecimal("1.5");
		Holding holding = holding(fixture, quantity);
		holdingRepository.saveAndFlush(holding);
		Order filledBuy = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, OrderType.MARKET,
			quantity, fixture.attempt().getId(), 1L, "compensation-filled", "c".repeat(64), NOW.minusMinutes(1)));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			filledBuy, fixture.account(), fixture.instrument(), null, OrderSide.BUY, BigDecimal.valueOf(90_000),
			quantity, 135_000L, 67L, null, NOW.minusMinutes(1), NOW.minusMinutes(1)));
		holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, quantity, BigDecimal.valueOf(90_000), 67L, NOW.minusMinutes(1), NOW.minusMinutes(1)));
		BigDecimal chartCurrentClose = chartService.getChart(fixture.user().getId(), Market.CRYPTO)
			.candles().get(29).close();

		PracticeAttemptResponse response = restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(2L);
		Order auditOrder = orderRepository.findByUserIdAndIdempotencyKey(
			fixture.user().getId(), "practice-restart:" + fixture.attempt().getId() + ":1").orElseThrow();
		assertThat(auditOrder.getSide()).isEqualTo(OrderSide.SELL);
		assertThat(auditOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(auditOrder.getQuantity()).isEqualByComparingTo(quantity);
		assertThat(auditOrder.getPracticeAttemptId()).isEqualTo(fixture.attempt().getId());
		assertThat(auditOrder.getPracticeAttemptRunNumber()).isEqualTo(1L);
		Trade auditTrade = tradeRepository.findByOrderId(auditOrder.getId()).orElseThrow();
		assertThat(auditTrade.getSide()).isEqualTo(OrderSide.SELL);
		assertThat(auditTrade.getQuantity()).isEqualByComparingTo(quantity);
		assertThat(auditTrade.getPrice()).isEqualByComparingTo(chartCurrentClose);
		assertThat(auditTrade.getRealizedPnl()).isNotNull();
		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(auditTrade.getId());
		assertThat(allocations).singleElement()
			.satisfies(allocation -> assertThat(allocation.getAllocatedQuantity()).isEqualByComparingTo(quantity));
		Holding emptied = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(emptied.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(emptied.isActive()).isFalse();
	}

	@Test
	void restartWithCompensatingSellResetsTutorialAccountToInitialValuesAndLeavesRealAccountUnchanged() {
		Fixture fixture = selectedFixture("tutorial-cash-compensation", Market.CRYPTO);
		BigDecimal quantity = new BigDecimal("1.5");
		Holding holding = holding(fixture, quantity);
		holdingRepository.saveAndFlush(holding);
		Order filledBuy = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, OrderType.MARKET,
			quantity, fixture.attempt().getId(), 1L, "tutorial-cash-compensation-filled", "e".repeat(64),
			NOW.minusMinutes(1)));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			filledBuy, fixture.account(), fixture.instrument(), null, OrderSide.BUY, BigDecimal.valueOf(90_000),
			quantity, 135_000L, 67L, null, NOW.minusMinutes(1), NOW.minusMinutes(1)));
		holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, quantity, BigDecimal.valueOf(90_000), 67L, NOW.minusMinutes(1), NOW.minusMinutes(1)));
		long realAccountCashBefore = fixture.account().getCashBalance();
		long realAccountRealizedPnlBefore = fixture.account().getRealizedPnl();

		PracticeAttemptResponse response = restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(10_000_000L);
		assertThat(response.tutorialRealizedPnl()).isZero();

		Order auditOrder = orderRepository.findByUserIdAndIdempotencyKey(
			fixture.user().getId(), "practice-restart:" + fixture.attempt().getId() + ":1").orElseThrow();
		Trade auditTrade = tradeRepository.findByOrderId(auditOrder.getId()).orElseThrow();
		assertThat(auditTrade.getRealizedPnl()).isNotNull();
		long cashIfResetHadRunBeforeCompensatingSell = 10_000_000L + auditTrade.getAmount() - auditTrade.getFee();
		assertThat(cashIfResetHadRunBeforeCompensatingSell).isGreaterThan(10_000_000L);

		Account realAccountAfter = accountRepository.findById(fixture.account().getId()).orElseThrow();
		assertThat(realAccountAfter.getCashBalance()).isEqualTo(realAccountCashBefore);
		assertThat(realAccountAfter.getRealizedPnl()).isEqualTo(realAccountRealizedPnlBefore);

		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(fixture.user().getId(), Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	@Test
	void repeatedRestartsAlwaysReturnTutorialAccountToExactlyInitialCashRegardlessOfPriorRunResult() {
		Fixture fixture = selectedFixture("tutorial-cash-repeat", Market.CRYPTO);
		long realAccountCashBefore = fixture.account().getCashBalance();
		long realAccountRealizedPnlBefore = fixture.account().getRealizedPnl();
		Holding holding = Holding.create(fixture.account(), fixture.instrument(), NOW);
		holdingRepository.saveAndFlush(holding);

		for (int run = 1; run <= 3; run++) {
			BigDecimal quantity = BigDecimal.valueOf(run);
			holding = holdingRepository.findById(holding.getId()).orElseThrow();
			holding.applyBuy(quantity, BigDecimal.valueOf(900_000), NOW.minusMinutes(1));
			holdingRepository.saveAndFlush(holding);
			Order filledBuy = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
				fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, OrderType.MARKET,
				quantity, fixture.attempt().getId(), run, "tutorial-cash-repeat-filled-" + run, "e".repeat(64),
				NOW.minusMinutes(1)));
			Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
				filledBuy, fixture.account(), fixture.instrument(), null, OrderSide.BUY, BigDecimal.valueOf(90_000),
				quantity, 90_000L * run, 67L, null, NOW.minusMinutes(1), NOW.minusMinutes(1)));
			holdingLotRepository.saveAndFlush(HoldingLot.create(
				holding, buyTrade, quantity, BigDecimal.valueOf(90_000), 67L, NOW.minusMinutes(1),
				NOW.minusMinutes(1)));

			restartService.restart(fixture.user().getId(), Market.CRYPTO);

			TutorialAccount tutorialAccount = tutorialAccountRepository
				.findByUserIdAndMarket(fixture.user().getId(), Market.CRYPTO)
				.orElseThrow();
			assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(tutorialAccount.getReservedCash()).isZero();
			assertThat(tutorialAccount.getRealizedPnl()).isZero();
			Account realAccountAfterRun = accountRepository.findById(fixture.account().getId()).orElseThrow();
			assertThat(realAccountAfterRun.getCashBalance()).isEqualTo(realAccountCashBefore);
			assertThat(realAccountAfterRun.getRealizedPnl()).isEqualTo(realAccountRealizedPnlBefore);

			if (run < 3) {
				PracticeAttempt attempt = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
				attempt.selectInstrument(fixture.instrument(), NOW.minusMinutes(10), NOW.toLocalDate(), 123L,
					(short)1, null, NOW.minusMinutes(10));
				attemptRepository.saveAndFlush(attempt);
			}
		}
	}

	@Test
	void restartWithZeroNetFilledQuantityResetsAlreadyDisturbedTutorialAccountToExactInitialValues() {
		Fixture fixture = selectedFixture("tutorial-cash-zero-net", Market.CRYPTO);
		tutorialAccountRepository.saveAndFlush(disturbedTutorialAccount(fixture.user(), Market.CRYPTO));

		restartService.restart(fixture.user().getId(), Market.CRYPTO);

		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(fixture.user().getId(), Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	@Test
	void restartWithoutSelectedInstrumentStillResetsAlreadyDisturbedTutorialAccountToExactInitialValues() {
		User user = user("tutorial-cash-no-instrument");
		account(user, Market.CRYPTO);
		attemptRepository.saveAndFlush(PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		tutorialAccountRepository.saveAndFlush(disturbedTutorialAccount(user, Market.CRYPTO));

		restartService.restart(user.getId(), Market.CRYPTO);

		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	@Test
	void restartFailureDueToHoldingMismatchLeavesTutorialAccountUntouched() {
		Fixture fixture = selectedFixture("tutorial-cash-rollback", Market.CRYPTO);
		Holding holding = holding(fixture, BigDecimal.ONE);
		holdingRepository.saveAndFlush(holding);
		fixture.account().reserveCash(100_050L);
		accountRepository.saveAndFlush(fixture.account());
		orderRepository.saveAndFlush(attributedPending(fixture, OrderSide.BUY, "0.1", 1L, "rollback-pending"));
		Order filledBuy = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, OrderType.MARKET,
			new BigDecimal("1.5"), fixture.attempt().getId(), 1L, "rollback-filled", "f".repeat(64), NOW));
		tradeRepository.saveAndFlush(Trade.of(
			filledBuy, fixture.account(), fixture.instrument(), null, OrderSide.BUY, BigDecimal.valueOf(90_000),
			new BigDecimal("1.5"), 135_000L, 67L, null, NOW, NOW));
		TutorialAccount disturbed = tutorialAccountRepository
			.saveAndFlush(disturbedTutorialAccount(fixture.user(), Market.CRYPTO));
		Long tutorialAccountId = disturbed.getId();

		assertThatThrownBy(() -> restartService.restart(fixture.user().getId(), Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		TutorialAccount stillDisturbed = tutorialAccountRepository.findById(tutorialAccountId).orElseThrow();
		assertThat(stillDisturbed.getCashBalance()).isEqualTo(disturbed.getCashBalance());
		assertThat(stillDisturbed.getReservedCash()).isEqualTo(disturbed.getReservedCash());
		assertThat(stillDisturbed.getRealizedPnl()).isEqualTo(disturbed.getRealizedPnl());
	}

	private TutorialAccount disturbedTutorialAccount(User user, Market market) {
		TutorialAccount account = TutorialAccount.create(
			user, Market.valueOf(market.name()), NOW.minusMinutes(30));
		account.deductCash(1_500_000L);
		account.reserveCash(200_000L);
		account.addRealizedPnl(-350_000L);
		return account;
	}

	private TutorialAccount reservedTutorialAccount(User user, Market market, long reservedCash) {
		TutorialAccount account = TutorialAccount.create(
			user, Market.valueOf(market.name()), NOW.minusMinutes(30));
		account.reserveCash(reservedCash);
		return tutorialAccountRepository.saveAndFlush(account);
	}

	@Test
	void twoConcurrentRestartsSerializeAndCancelCurrentRunOnlyOnce() throws Exception {
		Fixture fixture = selectedFixture("concurrent", Market.CRYPTO);
		reservedTutorialAccount(fixture.user(), Market.CRYPTO, 100_050L);
		Order pending = orderRepository.saveAndFlush(
			attributedPending(fixture, OrderSide.BUY, "0.1", 1L, "concurrent-pending"));
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> {
				start.await();
				return restartService.restart(fixture.user().getId(), Market.CRYPTO).runNumber();
			});
			var second = executor.submit(() -> {
				start.await();
				return restartService.restart(fixture.user().getId(), Market.CRYPTO).runNumber();
			});
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(2L, 3L);
		} finally {
			executor.shutdownNow();
		}

		assertThat(attemptRepository.findById(fixture.attempt().getId()).orElseThrow().getRunNumber()).isEqualTo(3L);
		assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(accountRepository.findById(fixture.account().getId()).orElseThrow().getReservedCash()).isZero();
	}

	@Test
	void completedRestartCleansUpAndRestartsLikeIncompleteAttempt() {
		Fixture fixture = selectedFixture("completed", Market.CRYPTO);
		reservedTutorialAccount(fixture.user(), Market.CRYPTO, 100_050L);
		Order pending = orderRepository.saveAndFlush(
			attributedPending(fixture, OrderSide.BUY, "0.1", 1L, "completed-pending"));
		jdbcTemplate.update(
			"UPDATE practice_attempts SET status = 'COMPLETED', completed_at = ?, updated_at = ? WHERE id = ?",
			NOW.minusDays(1), NOW.minusDays(1), fixture.attempt().getId());
		long orderCount = orderRepository.count();
		long tradeCount = tradeRepository.count();
		long cashBalance = fixture.account().getCashBalance();

		PracticeAttemptResponse response = restartService.restart(fixture.user().getId(), Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("ACTIVE");
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.instrumentId()).isNull();
		assertThat(response.completedAt()).isNull();
		assertThat(orderRepository.count()).isEqualTo(orderCount);
		assertThat(tradeRepository.count()).isEqualTo(tradeCount);
		assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		Account cleaned = accountRepository.findById(fixture.account().getId()).orElseThrow();
		assertThat(cleaned.getCashBalance()).isEqualTo(cashBalance);
		assertThat(cleaned.getReservedCash()).isZero();
		PracticeAttempt persisted = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
		assertThat(persisted.getStatus().name()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(persisted.getRunNumber()).isEqualTo(2L);
	}

	@Test
	void legacyCompletedRealInstrumentRestartKeepsRealPortfolioAndStartsNewRun() {
		User user = user("legacy-real");
		Account account = account(user, Market.CRYPTO);
		Instrument realInstrument = realInstrument("legacy-real", Market.CRYPTO);
		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW.minusDays(2));
		attempt.selectInstrument(
			realInstrument, NOW.minusDays(1), NOW.toLocalDate().minusDays(1), 456L, (short)1, null, NOW.minusDays(1));
		attemptRepository.saveAndFlush(attempt);
		jdbcTemplate.update(
			"UPDATE practice_attempts SET status = 'COMPLETED', completed_at = ?, updated_at = ? WHERE id = ?",
			NOW.minusDays(1), NOW.minusDays(1), attempt.getId());
		Holding realHolding = Holding.create(account, realInstrument, NOW.minusDays(1));
		realHolding.applyBuy(new BigDecimal("2"), BigDecimal.valueOf(900_000), NOW.minusDays(1));
		holdingRepository.saveAndFlush(realHolding);
		Order generalOrder = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, realInstrument, OrderSide.BUY, new BigDecimal("0.3"), LIMIT_PRICE,
			"legacy-general" + UUID.randomUUID(), "d".repeat(64), NOW));
		long orderCount = orderRepository.count();
		long tradeCount = tradeRepository.count();

		PracticeAttemptResponse response = restartService.restart(user.getId(), Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("ACTIVE");
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.instrumentId()).isNull();
		assertThat(response.completedAt()).isNull();
		PracticeAttempt persisted = attemptRepository.findById(attempt.getId()).orElseThrow();
		assertThat(persisted.getStatus().name()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(persisted.getRunNumber()).isEqualTo(2L);
		Holding untouched = holdingRepository.findById(realHolding.getId()).orElseThrow();
		assertThat(untouched.getQuantity()).isEqualByComparingTo("2");
		assertThat(untouched.isActive()).isTrue();
		assertThat(orderRepository.findById(generalOrder.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.count()).isEqualTo(orderCount);
		assertThat(tradeRepository.count()).isEqualTo(tradeCount);
	}

	@Test
	void restartWithoutSelectedInstrumentIncrementsRunWithNoLedgerRows() {
		User user = user("no-instrument");
		account(user, Market.CRYPTO);
		PracticeAttempt attempt = attemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));

		PracticeAttemptResponse response = restartService.restart(user.getId(), Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM orders WHERE practice_attempt_id = ? AND practice_attempt_run_number = ?",
			Long.class, attempt.getId(), 1L)).isZero();
	}

	private Fixture selectedFixture(String scenario, Market market) {
		User user = user(scenario);
		return selectedFixtureForExistingUser(user, scenario, market);
	}

	private Fixture selectedFixtureForExistingUser(User user, String scenario, Market market) {
		Account account = account(user, market);
		Instrument instrument = instrument(scenario, market);
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

	private Account account(User user, Market market) {
		Account account = accountRepository.saveAndFlush(Account.create(
			user, Market.valueOf(market.name()), NOW));
		accountIds.add(account.getId());
		return account;
	}

	private Instrument instrument(String scenario, Market market) {
		Instrument instrument = Instrument.create(
			market, "T" + UUID.randomUUID().toString().substring(0, 8), scenario,
			BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		return instrument;
	}

	private Instrument realInstrument(String scenario, Market market) {
		Instrument instrument = Instrument.create(
			market, "R" + UUID.randomUUID().toString().substring(0, 8), scenario,
			BigDecimal.ONE, 5_000L, true, NOW);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		return instrument;
	}

	private Holding holding(Fixture fixture, BigDecimal quantity) {
		Holding holding = Holding.create(fixture.account(), fixture.instrument(), NOW);
		holding.applyBuy(quantity, BigDecimal.valueOf(900_000), NOW);
		return holding;
	}

	private Order attributedPending(
		Fixture fixture, OrderSide side, String quantity, long runNumber, String key) {
		return Order.createLimitPendingForPracticeAttempt(
			fixture.user(), fixture.account(), fixture.instrument(), side, new BigDecimal(quantity), LIMIT_PRICE,
			fixture.attempt().getId(), runNumber, key + UUID.randomUUID(), "a".repeat(64), NOW);
	}

	private Order ordinaryPending(Fixture fixture, String quantity, String key) {
		return Order.createLimitPending(
			fixture.user(), fixture.account(), fixture.instrument(), OrderSide.BUY, new BigDecimal(quantity),
			LIMIT_PRICE, key + UUID.randomUUID(), "b".repeat(64), NOW);
	}

	private record Fixture(User user, Account account, Instrument instrument, PracticeAttempt attempt) {
	}
}
