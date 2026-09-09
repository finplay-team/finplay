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
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.TradeRepository;
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
class PracticeScenarioTickIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 19, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("9950");
	private static final BigDecimal FILL_PRICE_AT_MINUTE_FOUR = new BigDecimal("9941.58000000");
	private static final BigDecimal RUMOR_STAGE_LOW = new BigDecimal("9750.00000000");

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
	private PracticeAttemptChartService chartService;
	@Autowired
	private TradeRepository tradeRepository;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void tickSettlesTheLimitOrderAtThePriceOfTheFirstQualifyingSkippedMinute() {
		Fixture fixture = scenarioFixture("scenario-tick", "ACT2_RUMOR");

		LimitOrderResponse created = limitOrderService.createLimitOrder(
			fixture.user().getId(), "scenario-tick-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(
				Market.CRYPTO, fixture.instrument().getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));

		clock.set(BASE_NOW.plusSeconds(22));
		chartService.tick(fixture.user().getId(), Market.CRYPTO);

		assertThat(tradeRepository.findByOrderId(created.orderId()))
			.get()
			.satisfies(trade -> assertThat(trade.getPrice()).isEqualByComparingTo(FILL_PRICE_AT_MINUTE_FOUR));

		PracticeAttempt advanced = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
		assertThat(advanced.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(advanced.getScenarioStageElapsedSeconds()).isEqualTo(22L);
		assertThat(advanced.getScenarioProgressUpdatedAt()).isEqualTo(BASE_NOW.plusSeconds(22));
		assertThat(advanced.getScenarioCandleLow()).isEqualByComparingTo(RUMOR_STAGE_LOW);
	}

	@Test
	void limitOrderPlacedAfterTheScriptFinishedIsStillSettledByTick() {
		Fixture fixture = scenarioFixture("scenario-finished", "ACT4_CRASH");
		fixture.attempt().moveScenarioCursor("ACT4_CRASH", 60L);
		attemptRepository.saveAndFlush(fixture.attempt());

		LimitOrderResponse created = limitOrderService.createLimitOrder(
			fixture.user().getId(), "scenario-finished-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(
				Market.CRYPTO, fixture.instrument().getId(), OrderSide.BUY, QUANTITY, new BigDecimal("8000")));

		clock.set(BASE_NOW.plusSeconds(22));
		chartService.tick(fixture.user().getId(), Market.CRYPTO);

		assertThat(tradeRepository.findByOrderId(created.orderId()))
			.get()
			.satisfies(trade -> assertThat(trade.getPrice()).isEqualByComparingTo(new BigDecimal("7900.00000000")));
	}

	@Test
	void chartQueryDoesNotAdvanceTheCursor() {
		Fixture fixture = scenarioFixture("scenario-chart", "ACT1_RISE");

		clock.set(BASE_NOW.plusSeconds(22));
		chartService.getChart(fixture.user().getId(), Market.CRYPTO);

		PracticeAttempt unchanged = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
		assertThat(unchanged.getScenarioStageElapsedSeconds()).isZero();
		assertThat(unchanged.getScenarioProgressUpdatedAt()).isEqualTo(BASE_NOW);
	}

	private Fixture scenarioFixture(String scenario, String stageId) {
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + UUID.randomUUID().toString().substring(0, 8) + "@finplay.com", "hash",
			scenario + "-" + UUID.randomUUID().toString().substring(0, 8), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + UUID.randomUUID().toString().substring(0, 8), scenario, BigDecimal.ONE, 0L, true,
			BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);

		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), Market.CRYPTO, BASE_NOW.minusHours(1));
		attempt.selectInstrument(
			instrument, BASE_NOW.minusMinutes(10), BASE_NOW.toLocalDate(), 123L,
			TutorialPriceGenerator.VERSION_2, null, BASE_NOW.minusMinutes(10));
		attempt.startScenarioProgress(stageId, new BigDecimal("10180.00000000"), BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		orderService.createOrder(user.getId(), "scenario-tick-warmup-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));
		orderService.createOrder(user.getId(), "scenario-tick-warmup-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET", QUANTITY));
		return new Fixture(user, account, instrument, attempt);
	}

	private record Fixture(User user, Account account, Instrument instrument, PracticeAttempt attempt) {
	}
}
