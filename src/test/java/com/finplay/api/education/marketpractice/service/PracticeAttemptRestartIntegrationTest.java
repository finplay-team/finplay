// 튜토리얼 attempt 재시작의 MySQL 원자성, 행 격리와 동시 직렬화를 실제 트랜잭션으로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.account.repository.TutorialAccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
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
		fixture.account().reserveCash(600_300L);
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
		fixture.account().reserveCash(100_050L);
		accountRepository.saveAndFlush(fixture.account());
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
		assertThat(accountRepository.findById(fixture.account().getId()).orElseThrow().getReservedCash())
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

	// 047 TUTORIAL-CASH-ISOL-003: 재시작 보상매도(PracticeRunRestartOrderService.createCompensatingSell)도
	// PortfolioSellService.finalizeSellRealizedPnl을 공유하므로, 샌드박스 종목(selectedFixture()가 만드는
	// instrument는 항상 tutorialSample=true)이면 실제 Account.cashBalance·realizedPnl은 전혀 변하지 않는다.
	// TUTORIAL-CASH-ISOL-006(cleanupCurrentRun의 정리 트랜잭션 안에서 보상매도 *이후* 튜토리얼 계좌 리셋이
	// 호출됨)이 추가된 뒤로는, 보상매도가 튜토리얼 계좌에 반영한 대금·실현손익이 같은 트랜잭션에서 바로 뒤이은
	// 리셋으로 덮어써져 결과적으로 항상 초기값(1000만원/0/0)으로 끝나야 한다 — "거래 내역 자체가 리셋된다"는
	// 시나리오가 재시작 자체가 만든 보상매도에도 예외 없이 적용된다는 뜻이다. 리셋이 보상매도보다 먼저
	// 일어났다면(순서가 반대였다면) 튜토리얼 계좌는 1000만원이 아니라 "1000만원 + 보상매도 대금"으로 끝났을
	// 것이므로, 정확히 1000만원인지를 절대값으로 확인하는 것 자체가 순서 검증이다.
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

		restartService.restart(fixture.user().getId(), Market.CRYPTO);

		Order auditOrder = orderRepository.findByUserIdAndIdempotencyKey(
			fixture.user().getId(), "practice-restart:" + fixture.attempt().getId() + ":1").orElseThrow();
		Trade auditTrade = tradeRepository.findByOrderId(auditOrder.getId()).orElseThrow();
		assertThat(auditTrade.getRealizedPnl()).isNotNull();
		// 매도 대금(amount - fee)은 가격이 양수인 한 항상 0보다 크므로, 리셋이 보상매도보다 먼저 일어났다면
		// 튜토리얼 계좌 현금은 반드시 1000만원을 넘어섰을 것이다 — 아래 절대값 검증과 대비되는 반증 값이다.
		long cashIfResetHadRunBeforeCompensatingSell = 10_000_000L + auditTrade.getAmount() - auditTrade.getFee();
		assertThat(cashIfResetHadRunBeforeCompensatingSell).isGreaterThan(10_000_000L);

		// 실제 Account는 이 보상매도·리셋 어느 쪽으로도 현금·실현손익이 전혀 변하지 않는다.
		Account realAccountAfter = accountRepository.findById(fixture.account().getId()).orElseThrow();
		assertThat(realAccountAfter.getCashBalance()).isEqualTo(realAccountCashBefore);
		assertThat(realAccountAfter.getRealizedPnl()).isEqualTo(realAccountRealizedPnlBefore);

		// 튜토리얼 계좌는 보상매도 대금·손익과 무관하게 정확히 초기값으로 리셋된다(047 설계 판단 "계좌 생성
		// 시점" — 여기서 최초 생성된 뒤 같은 트랜잭션에서 곧바로 리셋됨).
		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	// TUTORIAL-CASH-ISOL-006: 재시작을 반복할 때마다(순체결수량이 있어 보상매도가 생기든 없든) 튜토리얼
	// 계좌가 매번 정확히 초기값으로 돌아오는지 확인한다 — 이슈 #450이 노린 "재시작 반복으로 잔고가 계속
	// 불어나는" 시나리오가 튜토리얼 계좌 안에서도 성립하지 않음을 실측한다. 매 run마다 새 보유 포지션을 만들어
	// 정리(보상매도)를 유발시키고, 실제 Account.cashBalance는 루프 내내 최초 값 그대로임도 함께 확인한다.
	@Test
	void repeatedRestartsAlwaysReturnTutorialAccountToExactlyInitialCashRegardlessOfPriorRunResult() {
		Fixture fixture = selectedFixture("tutorial-cash-repeat", Market.CRYPTO);
		long realAccountCashBefore = fixture.account().getCashBalance();
		long realAccountRealizedPnlBefore = fixture.account().getRealizedPnl();
		// holdings(account_id, instrument_id)에 유니크 제약이 있어 run마다 새 Holding 행을 만들 수 없다 — 매
		// run마다 직전 보상매도로 비워진(quantity 0, isActive false) 같은 행을 재매수로 되살려 재사용한다.
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
				.findByUserIdAndMarket(fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO)
				.orElseThrow();
			assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(tutorialAccount.getReservedCash()).isZero();
			assertThat(tutorialAccount.getRealizedPnl()).isZero();
			Account realAccountAfterRun = accountRepository.findById(fixture.account().getId()).orElseThrow();
			assertThat(realAccountAfterRun.getCashBalance()).isEqualTo(realAccountCashBefore);
			assertThat(realAccountAfterRun.getRealizedPnl()).isEqualTo(realAccountRealizedPnlBefore);

			// 다음 run을 위해 종목을 다시 선택한다(재시작 직후 상태는 SELECTING_INSTRUMENT).
			if (run < 3) {
				PracticeAttempt attempt = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
				attempt.selectInstrument(fixture.instrument(), NOW.minusMinutes(10), NOW.toLocalDate(), 123L,
					(short)1, NOW.minusMinutes(10));
				attemptRepository.saveAndFlush(attempt);
			}
		}
	}

	// TUTORIAL-CASH-ISOL-006: 순체결수량이 0이라 보상매도 없이 즉시 반환되는 경로도, 이번 run에서 이미
	// 매수·예약으로 흔들린 튜토리얼 계좌 잔고를 절대값(1000만원/0/0)으로 되돌려야 한다 — "이전보다 줄었다"가
	// 아니라 정확한 초기값인지를 확인한다.
	@Test
	void restartWithZeroNetFilledQuantityResetsAlreadyDisturbedTutorialAccountToExactInitialValues() {
		Fixture fixture = selectedFixture("tutorial-cash-zero-net", Market.CRYPTO);
		tutorialAccountRepository.saveAndFlush(disturbedTutorialAccount(fixture.user(), Market.CRYPTO));

		restartService.restart(fixture.user().getId(), Market.CRYPTO);

		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(fixture.user().getId(), com.finplay.api.account.domain.Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	// TUTORIAL-CASH-ISOL-006: 종목을 아직 선택하지 않은 채(instrumentId == null) 재시작해도 리셋은 그대로
	// 적용된다 — 정리 대상 주문·보유가 없는 경로라고 해서 리셋이 생략되지 않는지 확인한다.
	@Test
	void restartWithoutSelectedInstrumentStillResetsAlreadyDisturbedTutorialAccountToExactInitialValues() {
		User user = user("tutorial-cash-no-instrument");
		account(user, Market.CRYPTO);
		attemptRepository.saveAndFlush(PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		tutorialAccountRepository.saveAndFlush(disturbedTutorialAccount(user, Market.CRYPTO));

		restartService.restart(user.getId(), Market.CRYPTO);

		TutorialAccount tutorialAccount = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), com.finplay.api.account.domain.Market.CRYPTO).orElseThrow();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getRealizedPnl()).isZero();
	}

	// TUTORIAL-CASH-ISOL-006: 정리 자체가 실패(BusinessException)하면 재시작이 완료된 게 아니므로 튜토리얼
	// 계좌 리셋도 일어나지 않아야 한다 — 이미 존재하는 롤백 시나리오(홀딩 불일치)에 튜토리얼 계좌 불변 검증만
	// 추가한다.
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

	// 리셋 이전에 이번 run 안에서 매수·예약·손익이 이미 발생한 것처럼 흔들어 둔 튜토리얼 계좌를 만든다.
	private TutorialAccount disturbedTutorialAccount(User user, Market market) {
		TutorialAccount account = TutorialAccount.create(
			user, com.finplay.api.account.domain.Market.valueOf(market.name()), NOW.minusMinutes(30));
		account.deductCash(1_500_000L);
		account.reserveCash(200_000L);
		account.addRealizedPnl(-350_000L);
		return account;
	}

	@Test
	void twoConcurrentRestartsSerializeAndCancelCurrentRunOnlyOnce() throws Exception {
		Fixture fixture = selectedFixture("concurrent", Market.CRYPTO);
		fixture.account().reserveCash(100_050L);
		accountRepository.saveAndFlush(fixture.account());
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
		fixture.account().reserveCash(100_050L);
		accountRepository.saveAndFlush(fixture.account());
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

	// V32 샌드박스 종목 도입 이전에 실제 종목으로 완료한 legacy 완료자 재현이다 (이슈 #433).
	@Test
	void legacyCompletedRealInstrumentRestartKeepsRealPortfolioAndStartsNewRun() {
		User user = user("legacy-real");
		Account account = account(user, Market.CRYPTO);
		Instrument realInstrument = realInstrument("legacy-real", Market.CRYPTO);
		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW.minusDays(2));
		attempt.selectInstrument(
			realInstrument, NOW.minusDays(1), NOW.toLocalDate().minusDays(1), 456L, (short)1, NOW.minusDays(1));
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

	private Account account(User user, Market market) {
		Account account = accountRepository.saveAndFlush(Account.create(
			user, com.finplay.api.account.domain.Market.valueOf(market.name()), NOW));
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
