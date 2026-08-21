// 생성기 버전 2 tick이 대본 커서를 밀면서 건너뛴 가상 분마다 지정가를 정산하는지 실제 MySQL로 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
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
	// 2막-a 루머 구간의 분별 배율은 1.018 / 1.014791 / 1.00978 / 1.00205 / 0.994158 / 0.989518 / 0.980762 /
	// 0.975다. 기준가 10,000원이므로 이 지정가는 **4번째 분에서 처음** 조건을 만족한다.
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

	// SCENARIO-013 — tick 종점 가격 하나로만 판정하면 30초 만에 tick이 올 때 그 사이 가상 분의 극값을 못 봐
	// -3%로 걸어 둔 손절이 -10% 넘는 가격에 체결된다. 조건을 처음 만족한 분의 가격으로 체결돼야 한다.
	@Test
	void tickSettlesTheLimitOrderAtThePriceOfTheFirstQualifyingSkippedMinute() {
		Fixture fixture = scenarioFixture("scenario-tick", "ACT2_RUMOR");

		LimitOrderResponse created = limitOrderService.createLimitOrder(
			fixture.user().getId(), "scenario-tick-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(
				Market.CRYPTO, fixture.instrument().getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));

		// 직전 tick으로부터 22초 = 7 가상 분이 지난 상태에서 tick이 온다.
		clock.set(BASE_NOW.plusSeconds(22));
		chartService.tick(fixture.user().getId(), Market.CRYPTO);

		assertThat(tradeRepository.findByOrderId(created.orderId()))
			.get()
			.satisfies(trade -> assertThat(trade.getPrice()).isEqualByComparingTo(FILL_PRICE_AT_MINUTE_FOUR));

		PracticeAttempt advanced = attemptRepository.findById(fixture.attempt().getId()).orElseThrow();
		assertThat(advanced.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(advanced.getScenarioStageElapsedSeconds()).isEqualTo(22L);
		assertThat(advanced.getScenarioProgressUpdatedAt()).isEqualTo(BASE_NOW.plusSeconds(22));
		// 순회가 지나간 모든 분의 극값이 진행 중 봉에 담긴다 — 지나온 경로는 복원할 수 없다.
		assertThat(advanced.getScenarioCandleLow()).isEqualByComparingTo(RUMOR_STAGE_LOW);
	}

	// 세 리뷰어가 함께 찾은 결함의 통합 회귀 방어 — 대본이 끝난 뒤 접수한 지정가도 tick이 체결한다.
	@Test
	void limitOrderPlacedAfterTheScriptFinishedIsStillSettledByTick() {
		Fixture fixture = scenarioFixture("scenario-finished", "ACT4_CRASH");
		// 4막 20분 = 60초를 다 쓴 FINISHED 상태를 만든다.
		fixture.attempt().moveScenarioCursor("ACT4_CRASH", 60L);
		attemptRepository.saveAndFlush(fixture.attempt());

		LimitOrderResponse created = limitOrderService.createLimitOrder(
			fixture.user().getId(), "scenario-finished-buy-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(
				Market.CRYPTO, fixture.instrument().getId(), OrderSide.BUY, QUANTITY, new BigDecimal("8000")));

		clock.set(BASE_NOW.plusSeconds(22));
		chartService.tick(fixture.user().getId(), Market.CRYPTO);

		// 마지막 구간의 마지막 분 가격(10,000 × 0.790)으로 체결된다.
		assertThat(tradeRepository.findByOrderId(created.orderId()))
			.get()
			.satisfies(trade -> assertThat(trade.getPrice()).isEqualByComparingTo(new BigDecimal("7900.00000000")));
	}

	// GET chart는 순수 조회다 — tick을 부르지 않으면 대본이 진행하지 않는다(041 plan §잔여 위험).
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
		// 대기 구간을 이미 지나 2막에 들어와 있는 사용자를 재현한다 — 이 항목의 검증 대상은 대본 저작이
		// 아니라 순회이므로 커서를 직접 세운다.
		attempt.startScenarioProgress(stageId, new BigDecimal("10180.00000000"), BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		// 049 ORDERBASICS-015 — 이 실행은 대본을 쓰므로(생성기 버전 2) 지정가 주문은 시장가 왕복을 마쳐야
		// 열린다. 이 항목의 검증 대상은 대본 저작·순회이지 게이트가 아니므로, 지정가를 거는 테스트들이
		// 막히지 않도록 시장가 왕복을 미리 마친다. 시장가 주문은 커서를 움직이지 않으므로(오직 tick만
		// 커서를 민다) 순회를 대상으로 하는 단언(진행 초·진행 갱신 시각)에 영향이 없다.
		orderService.createOrder(user.getId(), "scenario-tick-warmup-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));
		orderService.createOrder(user.getId(), "scenario-tick-warmup-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET", QUANTITY));
		return new Fixture(user, account, instrument, attempt);
	}

	private record Fixture(User user, Account account, Instrument instrument, PracticeAttempt attempt) {
	}
}
