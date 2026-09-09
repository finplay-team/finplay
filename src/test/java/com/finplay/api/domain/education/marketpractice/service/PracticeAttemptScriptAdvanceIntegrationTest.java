package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
class PracticeAttemptScriptAdvanceIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	private static final BigDecimal ORDER_BASICS_OPEN_PRICE = new BigDecimal("100000.00000000");
	private static final BigDecimal LIMIT_BUY_PRICE = new BigDecimal("95000");
	private static final BigDecimal LIMIT_SELL_PRICE = new BigDecimal("90000");
	private static final BigDecimal IDLE_ENTRY_PRICE = new BigDecimal("10000.00000000");

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
	private TradeService tradeService;
	@Autowired
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeAttemptScriptAdvanceService scriptAdvanceService;
	@Autowired
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private PracticeAttemptCanonicalPriceService canonicalPriceService;
	@Autowired
	private TutorialAccountService tutorialAccountService;
	@Autowired
	private PracticeEntryComparisonService entryComparisonService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void completingBothRoundTripsThenAdvancingKeepsRunAndCashAndOpensTheStoryScript() {
		Fixture fixture = orderBasicsRun("advance-happy");

		marketRoundTrip(fixture);
		limitRoundTrip(fixture);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isTrue();
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isTrue();
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);

		long cashBeforeAdvance = tutorialAccountService
			.find(fixture.userId(), Market.CRYPTO)
			.orElseThrow()
			.getCashBalance();

		PracticeAttemptResponse response = scriptAdvanceService.advanceScript(fixture.userId(), Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(1L);
		assertThat(response.tutorialCashBalance()).isEqualTo(cashBeforeAdvance);
		PracticeAttempt advanced = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(advanced.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
		assertThat(advanced.getScenarioStageId()).isNull();
		assertThat(advanced.getScenarioCandleOpen()).isNull();
		PracticeStageProgressResponse progressAfterAdvance = stageProgressCalculationService.calculate(advanced);
		assertThat(progressAfterAdvance.marketBuySellCompleted()).isTrue();
		assertThat(progressAfterAdvance.limitBuySellCompleted()).isTrue();

		LocalDateTime afterAdvanceTick = BASE_NOW.plusSeconds(43);
		clock.set(afterAdvanceTick);
		chartService.tick(fixture.userId(), Market.CRYPTO);

		PracticeAttempt ticked = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(ticked.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(canonicalPriceService.canonicalPrice(ticked, afterAdvanceTick))
			.isEqualByComparingTo(IDLE_ENTRY_PRICE);

		PracticeAttemptResponse presetResponse = attemptService
			.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.BALANCED);
		assertThat(presetResponse.selectedExitPreset()).isEqualTo("BALANCED");
	}

	@Test
	void entriesCarryTheScriptIdOfTheStageTheyWereOpenedIn() {
		Fixture fixture = orderBasicsRun("advance-entries");

		marketRoundTrip(fixture);
		limitRoundTrip(fixture);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isTrue();
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isTrue();
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);

		scriptAdvanceService.advanceScript(fixture.userId(), Market.CRYPTO);
		PracticeAttempt advanced = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(advanced.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);

		clock.set(BASE_NOW.plusSeconds(50));
		buy(fixture);

		List<PracticeEntryResponse> entries = entryComparisonService.findCurrentRunEntries(advanced, null);

		assertThat(entries).hasSize(3);
		assertThat(entries.get(0).entrySequence()).isEqualTo(1);
		assertThat(entries.get(0).scenarioScriptId()).isEqualTo("CRYPTO_ORDER_BASICS_V1");
		assertThat(entries.get(1).entrySequence()).isEqualTo(2);
		assertThat(entries.get(1).scenarioScriptId()).isEqualTo("CRYPTO_ORDER_BASICS_V1");
		assertThat(entries.get(2).entrySequence()).isEqualTo(3);
		assertThat(entries.get(2).scenarioScriptId()).isEqualTo("CRYPTO_STORY_V1");
	}

	@Test
	void advanceScriptIsRejectedWith409WhileHoldingAPositionInTheCurrentRun() {
		Fixture fixture = orderBasicsRun("advance-holding");

		marketRoundTrip(fixture);
		limitRoundTrip(fixture);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isTrue();
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isTrue();

		clock.set(BASE_NOW.plusSeconds(50));
		buy(fixture);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);

		assertThatThrownBy(() -> scriptAdvanceService.advanceScript(fixture.userId(), Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));

		PracticeAttempt unchanged = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(unchanged.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}

	@Test
	void advanceScriptCancelsAPendingBuyLimitOrderStillOpenInTheCurrentRun() {
		Fixture fixture = orderBasicsRun("advance-pending-buy");

		marketRoundTrip(fixture);
		limitRoundTrip(fixture);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isTrue();
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isTrue();
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);

		long cashBeforePendingOrder = tutorialAccount(fixture).getCashBalance();
		clock.set(BASE_NOW.plusSeconds(50));
		LimitOrderResponse pendingBuy = limitOrderService.createLimitOrder(
			fixture.userId(), "advance-pending-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, QUANTITY,
				new BigDecimal("70000")));
		assertThat(pendingBuy.status()).isEqualTo("PENDING");
		assertThat(tutorialAccount(fixture).getAvailableCash())
			.as("매수 지정가 접수 시점에 현금이 예약된다")
			.isLessThan(cashBeforePendingOrder);

		scriptAdvanceService.advanceScript(fixture.userId(), Market.CRYPTO);

		assertThat(orderRepository.findById(pendingBuy.orderId()).orElseThrow().getStatus())
			.as("전환 정리가 PENDING 매수 지정가도 취소해야 한다")
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(tutorialAccount(fixture).getAvailableCash())
			.as("취소로 예약이 전부 해제돼 현금 전액이 다시 가용해야 한다")
			.isEqualTo(cashBeforePendingOrder);
		assertThat(tutorialAccount(fixture).getCashBalance()).isEqualTo(cashBeforePendingOrder);
	}

	private com.finplay.api.domain.account.entity.TutorialAccount tutorialAccount(Fixture fixture) {
		return tutorialAccountService.find(fixture.userId(), Market.CRYPTO)
			.orElseThrow();
	}

	private void marketRoundTrip(Fixture fixture) {
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);
	}

	private void limitRoundTrip(Fixture fixture) {
		clock.set(BASE_NOW.plusSeconds(3));
		limitOrder(fixture, OrderSide.BUY, LIMIT_BUY_PRICE);

		clock.set(BASE_NOW.plusSeconds(33));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);

		clock.set(BASE_NOW.plusSeconds(39));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);

		clock.set(BASE_NOW.plusSeconds(40));
		limitOrder(fixture, OrderSide.SELL, LIMIT_SELL_PRICE);

		clock.set(BASE_NOW.plusSeconds(42));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	private void limitOrder(Fixture fixture, OrderSide side, BigDecimal limitPrice) {
		limitOrderService.createLimitOrder(
			fixture.userId(), "advance-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), side, QUANTITY, limitPrice));
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "advance-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "advance-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private PracticeStageProgressResponse stageProgress(Fixture fixture) {
		return stageProgressCalculationService.calculate(
			attemptRepository.findById(fixture.attemptId()).orElseThrow());
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
		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		attempt.startScenarioProgress("ORDER_BASICS", ORDER_BASICS_OPEN_PRICE, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
