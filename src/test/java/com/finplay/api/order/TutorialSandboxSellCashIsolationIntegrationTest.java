// 047 TUTORIAL-CASH-ISOL-003: 지정가 매도 체결·OCO 손절익절 체결이 실제 서비스·MySQL(Testcontainers)로
// 샌드박스 종목이면 튜토리얼 계좌만 갱신하고 실제 Account.cashBalance·realizedPnl은 전혀 건드리지 않는지
// 검증하는 통합 테스트다. 두 경로 모두 PortfolioSellService.finalizeSellRealizedPnl을 공유하지만(047 tasks.md
// 항목4), 이 클래스가 검증하는 것은 그 공유 메서드 자체가 아니라 각 호출부(LimitOrderFillService.fillSell,
// ExitPlanFillService.executeMarketSell)가 실제로 그 메서드까지 올바르게 배선돼 있는지다 — 특히
// ExitPlanFillService는 047 이전까지 isTutorialSample 분기가 전혀 없던 경로였다(spec.md TUTORIAL-CASH-ISOL-010).
// 이슈 #461 이후 샌드박스 holding의 일반 OCO 생성 자체는 ExitPlanService가 막지만, 이미 존재하는 plan의 체결
// 현금 격리(이 파일이 검증하는 대상)는 여전히 유효해야 하므로 아래 테스트는 ExitPlanCreationService를 직접
// 호출해 plan을 만든다.
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.account.repository.TutorialAccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.order.service.ExitPlanCreationService;
import com.finplay.api.order.service.ExitPlanFillService;
import com.finplay.api.order.service.ExitPriceInputDto;
import com.finplay.api.order.service.LimitOrderFillService;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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
		// FK 역순: exit_plan_conditions/idempotency_keys → exit_plans → trade_allocations → holding_lots →
		// trades → orders → holdings → accounts → users (ExitPlanTriggerFillIntegrationTest와 동일 관례).
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

	// 시나리오: 샌드박스 종목 시장가 매수(홀딩 확보, 튜토리얼 계좌만 차감) → 같은 종목 지정가 매도 생성(홀딩 예약)
	// → LimitOrderFillService.fillIfPending 직접 호출로 결정적으로 체결(지정가를 그대로 체결가로 사용하는 일반
	// 경로 — attempt 귀속이 없으므로 가격 재조회 없이 order.getLimitPrice()가 곧 체결가다). 체결 후 실제
	// Account.cashBalance·realizedPnl은 매수·매도 전 구간 내내 최초값과 동일해야 하고, 튜토리얼 계좌만 매도
	// 대금·실현손익을 반영해야 한다.
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
			.findByUserIdAndMarket(user.getId(), com.finplay.api.account.domain.Market.CRYPTO)
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
		// amount = 0.5 * 12000 = 6000, fee = floor(6000 * 0.0005) = 3 (지정가를 그대로 체결가로 쓰는 일반 경로).
		assertThat(sellTrade.getAmount()).isEqualTo(6000L);
		assertThat(sellTrade.getFee()).isEqualTo(3L);

		Account realAccountAfterSell = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(realAccountAfterSell.getCashBalance()).isEqualTo(realCashAfterBuy);
		assertThat(realAccountAfterSell.getRealizedPnl()).isEqualTo(realRealizedPnlAfterBuy);

		TutorialAccount tutorialAccountAfterSell = tutorialAccountRepository
			.findByUserIdAndMarket(user.getId(), com.finplay.api.account.domain.Market.CRYPTO)
			.orElseThrow();
		assertThat(tutorialAccountAfterSell.getCashBalance())
			.isEqualTo(tutorialCashAfterBuy + sellTrade.getAmount() - sellTrade.getFee());
		assertThat(tutorialAccountAfterSell.getRealizedPnl()).isEqualTo(sellTrade.getRealizedPnl());
	}

	// 시나리오: 샌드박스 종목 시장가 매수 → holding에 일반 OCO(익절·손절) plan을 엔진(ExitPlanCreationService)으로
	// 직접 생성 → ExitPlanFillService.fillIfPending을 익절가 이상 currentPrice로 직접 호출해 결정적으로 체결(가격
	// 피드·리스너 배선은 ExitPlanTriggerFillIntegrationTest가 이미 별도로 검증하므로 여기서는 체결 서비스 자체의
	// 현금 격리만 본다). 047 이전까지 ExitPlanFillService·ExitPlanCreationService에는 isTutorialSample 분기가
	// 전혀 없었다(spec.md TUTORIAL-CASH-ISOL-010) — 이 테스트가 047 이후 실제로 튜토리얼 계좌로 격리되는지의
	// 직접 회귀 근거다. 이슈 #461(021 RISK-OCO-014)로 `ExitPlanService.create`가 샌드박스 holding의 생성 자체를
	// 409로 막게 됐으므로, 여기서는 그 호출부(`ExitPlanService`)를 우회하고 공용 엔진(`ExitPlanCreationService`)을
	// 직접 호출해 "이미 존재하는 plan의 체결 시 현금 격리"만 검증한다 — 이미 걸려 있던 legacy PENDING plan이
	// 체결될 때도 이 격리가 유지돼야 하기 때문이다(1안의 알려진 한계: 기존 plan은 구제 대상이 아니라 계속 존재할
	// 수 있다).
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
			.findByUserIdAndMarket(user.getId(), com.finplay.api.account.domain.Market.CRYPTO)
			.orElseThrow().getCashBalance();

		// exit_plans.stop_loss_price/take_profit_price는 DECIMAL(18,8)이다 — 곱셈으로 늘어난 소수자리를
		// 명시적으로 8자리로 맞추지 않으면 MySQL(strict mode)이 MysqlDataTruncation으로 거부한다(직접 재현).
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
			.findByUserIdAndMarket(user.getId(), com.finplay.api.account.domain.Market.CRYPTO)
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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
	}

	// 실제 시세 인프라(빗썸 poller·PriceStore)를 우회하는 결정적 샌드박스 가격(TutorialSampleInstrumentPriceService)을
	// 그대로 쓰기 위해 tutorialSample=true·tradable=true인 신규 CRYPTO 종목을 만든다(TutorialSandboxPracticeIntegrationTest·
	// PracticeAttemptRestartIntegrationTest의 fixture 관례와 동일). minOrderAmount는 0으로 두어 최소주문금액
	// 제약이 이 테스트의 관심사가 아니게 한다.
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
