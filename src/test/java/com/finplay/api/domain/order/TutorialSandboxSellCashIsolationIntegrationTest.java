package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import com.finplay.api.domain.order.service.ExitPriceInputDto;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
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
class TutorialSandboxSellCashIsolationIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private TradeRepository tradeRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private OrderService orderService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private LimitOrderFillService limitOrderFillService;
	@Autowired
	private ExitPlanCreationService exitPlanCreationService;
	@Autowired
	private ExitPlanFillService exitPlanFillService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void tearDown() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM exit_plan_conditions WHERE exit_plan_id IN "
				+ "(SELECT id FROM exit_plans WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM exit_plan_idempotency_keys WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM exit_plans WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM trade_allocations WHERE holding_lot_id IN "
				+ "(SELECT id FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)))", userId);
			jdbcTemplate.update("DELETE FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN (SELECT id FROM accounts WHERE user_id = ?))",
				userId);
			jdbcTemplate.update("DELETE FROM trades WHERE order_id IN "
				+ "(SELECT id FROM orders WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		instrumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", id));
		userIds.clear();
		instrumentIds.clear();
	}

	@Test
	void limitSellFillCreditsTutorialAccountAndLeavesRealAccountCashAndRealizedPnlUnchangedForTutorialSampleInstrument() {
		User user = createUser("tutorial-limit-sell");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("tutorial-limit-sell");

		orderService.createOrder(user.getId(), "tutorial-limit-sell-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("1")));

		long realCashAfterBuy = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
		long realRealizedPnlAfterBuy = accountRepository.findById(account.getId()).orElseThrow().getRealizedPnl();
		long tutorialCashAfterBuy = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow().getCashBalance();

		LimitOrderResponse limitOrder = limitOrderService.createLimitOrder(
			user.getId(), "tutorial-limit-sell-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(
				Market.CRYPTO, instrument.getId(), OrderSide.SELL, new BigDecimal("0.5"),
				new BigDecimal("12000")));

		limitOrderFillService.fillIfPending(limitOrder.orderId());

		Trade sellTrade = tradeRepository.findByOrderId(limitOrder.orderId()).orElseThrow();
		assertThat(sellTrade.getSide()).isEqualTo(OrderSide.SELL);
		assertThat(sellTrade.getRealizedPnl()).isNotNull();
		assertThat(sellTrade.getAmount()).isEqualTo(6000L);
		assertThat(sellTrade.getFee()).isEqualTo(3L);

		Account realAccountAfterSell = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(realAccountAfterSell.getCashBalance()).isEqualTo(realCashAfterBuy);
		assertThat(realAccountAfterSell.getRealizedPnl()).isEqualTo(realRealizedPnlAfterBuy);

		TutorialAccount tutorialAccountAfterSell = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccountAfterSell.getCashBalance())
			.isEqualTo(tutorialCashAfterBuy + sellTrade.getAmount() - sellTrade.getFee());
		assertThat(tutorialAccountAfterSell.getRealizedPnl()).isEqualTo(sellTrade.getRealizedPnl());
	}

	@Test
	void exitPlanTakeProfitFillCreditsTutorialAccountAndLeavesRealAccountCashAndRealizedPnlUnchangedForTutorialSampleInstrument() {
		User user = createUser("tutorial-oco-fill");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("tutorial-oco-fill");

		orderService.createOrder(user.getId(), "tutorial-oco-fill-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("10")));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal entryPrice = holding.getAveragePrice();

		long realCashAfterBuy = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
		long realRealizedPnlAfterBuy = accountRepository.findById(account.getId()).orElseThrow().getRealizedPnl();
		long tutorialCashAfterBuy = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow().getCashBalance();

		BigDecimal stopLoss = entryPrice.multiply(new BigDecimal("0.9")).setScale(8, java.math.RoundingMode.HALF_UP);
		BigDecimal takeProfit = entryPrice.multiply(new BigDecimal("1.1")).setScale(8, java.math.RoundingMode.HALF_UP);
		ExitPlanCreateCommandDto command = ExitPlanCreateCommandDto.general(
			user, holding, new BigDecimal("1"), ExitPriceInputDto.ofPrice(entryPrice, stopLoss, takeProfit),
			"h".repeat(64));
		var exitPlan = exitPlanCreationService.create(command);

		BigDecimal triggerPrice = takeProfit.add(BigDecimal.ONE);
		exitPlanFillService.fillIfPending(exitPlan.getId(), triggerPrice);

		Account realAccountAfterFill = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(realAccountAfterFill.getCashBalance()).isEqualTo(realCashAfterBuy);
		assertThat(realAccountAfterFill.getRealizedPnl()).isEqualTo(realRealizedPnlAfterBuy);

		Trade sellTrade = tradeRepository.findAll().stream()
			.filter(trade -> trade.getSide() == OrderSide.SELL && trade.getAccount().getId().equals(account.getId()))
			.findFirst()
			.orElseThrow();
		assertThat(sellTrade.getRealizedPnl()).isNotNull();

		TutorialAccount tutorialAccountAfterFill = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccountAfterFill.getCashBalance())
			.isEqualTo(tutorialCashAfterBuy + sellTrade.getAmount() - sellTrade.getFee());
		assertThat(tutorialAccountAfterFill.getRealizedPnl()).isEqualTo(sellTrade.getRealizedPnl());
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
