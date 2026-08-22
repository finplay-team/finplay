// 2단계 대본 실행에서 자동 OCO 예약만 빠지고 기준선·관찰·복기는 그대로 도는지 실제 MySQL로 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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

/**
 * 049 ORDERBASICS-022 — 2단계 대본(주문 방법 학습) 실행에서는 자동 손절·익절 예약을 만들지 않는다.
 *
 * <p><b>단위 테스트로 대신할 수 없다.</b> 확인해야 하는 것이 "예약 행이 없다"만이 아니라 <b>tick을 여러
 * 바퀴 돌려도 포지션이 살아 있다</b>이고, 그 판정은 {@code exit_plans}와 {@code holdings.reserved_quantity}와
 * 체결 원장이 함께 얽힌 실제 원장에서만 성립한다.
 *
 * <p><b>가장 위험한 회귀는 예약이 아니라 기준선이다.</b> {@code practice_risk_snapshots}까지 함께 빠지면
 * {@code PracticeAttemptEvidenceService.requireCurrentRun}이 {@code PRACTICE_EVIDENCE_MISSING}으로 던져
 * 그 실행의 관찰·복기가 통째로 깨진다 — 예약 개수만 세는 테스트는 그 상태를 초록으로 통과시킨다.
 *
 * <p><b>대조군이 반드시 있어야 한다.</b> 3단계(041) 실행에서 예약이 만들어지는지 함께 보지 않으면
 * 예약 생성을 통째로 없앤 구현도 초록이다. <b>052 EXITFREE-020 이후 그 대조군은 "매수가 자동으로
 * 만든다"가 아니라 "사용자가 직접 걸면 만들어진다"로 바뀌었다.</b>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeOrderBasicsNoAutoExitIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	// 2단계 대본은 기준가 100,000원에 ORDER_BASICS 한 구간뿐이고 0분 배율이 1.000000이다.
	private static final BigDecimal ORDER_BASICS_ENTRY_PRICE = new BigDecimal("100000.00000000");
	// 기본 프리셋(BALANCED, -3%/+5%)이 확정하는 두 선. 대본은 20 가상 분마다 112,000원과 88,000원에
	// 닿으므로 **예약이 살아 있으면 첫 바퀴 안에 반드시 발동한다.**
	private static final BigDecimal DEFAULT_STOP_LOSS = new BigDecimal("97000.00000000");
	private static final BigDecimal DEFAULT_TAKE_PROFIT = new BigDecimal("105000.00000000");
	private static final BigDecimal ORDER_BASICS_HIGH = new BigDecimal("112000.00000000");
	private static final BigDecimal ORDER_BASICS_LOW = new BigDecimal("88000.00000000");
	// 049 ORDERBASICS-015 게이트를 통과시키는 지정가 왕복 — PracticeAttemptScriptAdvanceIntegrationTest와
	// 같은 시각·가격이다(매수는 12번째 가상 분 92,946.6원, 매도는 13번째 가상 분 90,291.8원에서 처음 체결).
	private static final BigDecimal LIMIT_BUY_PRICE = new BigDecimal("95000");
	private static final BigDecimal LIMIT_SELL_PRICE = new BigDecimal("90000");
	// 041 대조군은 기존 OCO 통합 테스트와 같은 자리(2막-a 루머 0분)에 세운다.
	private static final BigDecimal STORY_ENTRY_PRICE = new BigDecimal("10180.00000000");
	// 진행 계산이 한 tick에 소비하는 상한이다(PracticeScenarioProgressService.MAX_TICK_GAP_SECONDS).
	// 30초 = 가상 10분이므로 한 바퀴(가상 20분)를 돌리려면 tick이 최소 두 번 필요하다.
	private static final int SECONDS_PER_TICK = 30;
	private static final int TICK_ROUNDS = 4;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private PracticeRiskSnapshotRepository riskSnapshotRepository;
	@Autowired
	private ExitPlanRepository exitPlanRepository;
	@Autowired
	private HoldingRepository holdingRepository;
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
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeExitPlanReservationService exitPlanReservationService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void orderBasicsBuyCreatesTheRiskBaselineButNoAutomaticExitPlan() {
		Fixture fixture = orderBasicsRun("ob-baseline");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		// 기준선은 그대로 만들어진다 — 여기가 관찰·복기 전체가 서 있는 자리다.
		PracticeRiskSnapshot entry = latestSnapshot(fixture);
		assertThat(entry.getEntrySequence()).isEqualTo(1);
		assertThat(entry.getExitPreset()).isEqualTo(ExitPreset.DEFAULT);
		assertThat(entry.getEntryPrice()).isEqualByComparingTo(ORDER_BASICS_ENTRY_PRICE);
		assertThat(entry.getStopLossPrice()).isEqualByComparingTo(DEFAULT_STOP_LOSS);
		assertThat(entry.getTakeProfitPrice()).isEqualByComparingTo(DEFAULT_TAKE_PROFIT);

		// 예약만 빠진다. 수량이 예약에 잡히지 않았다는 것까지 봐야 "행만 안 만들고 수량은 묶었다"를 막는다.
		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
	}

	/**
	 * <b>대조군.</b> 이 테스트가 없으면 예약 경로를 통째로 없앤 구현도 위 테스트를 통과한다.
	 *
	 * <p><b>052 EXITFREE-020으로 대조군의 내용이 바뀌었다.</b> 3단계 대본 실행도 이제 매수 체결로는 예약을
	 * 만들지 않는다 — 사용자가 직접 걸어야 생긴다. 그래서 "매수만으로는 안 생긴다"와 "사용자가 걸면
	 * 생긴다"를 한 테스트에서 함께 단언한다. 앞쪽만 두면 예약 경로를 없앤 구현이 초록이고, 뒤쪽만 두면
	 * 자동 예약이 되살아난 구현이 초록이다.
	 */
	@Test
	void storyScriptBuyCreatesNoAutomaticExitPlanButTheUserCanReserveOneDirectly() {
		Fixture fixture = storyRun("ob-control");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		assertThat(latestSnapshot(fixture).getEntrySequence()).isEqualTo(1);
		// 052 EXITFREE-020 — 매수 체결은 기준선까지만 만든다.
		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);

		// 사용자가 직접 건다 — 프리셋에 없던 조합(손절 2 + 익절 8)으로도 걸린다.
		clock.set(BASE_NOW.plusSeconds(2));
		exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("2"), new BigDecimal("8")));

		assertThat(exitPlans(fixture)).singleElement().satisfies(plan -> {
			assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
			// 예약 기준가는 대본 canonical price다 — 엔진 기본 경로의 사인파 항시 시세가 아니다.
			assertThat(plan.getBaselinePrice()).isEqualByComparingTo(STORY_ENTRY_PRICE);
			// 진입가는 그 진입의 체결가이고 두 선은 019 공식 그대로다.
			assertThat(plan.getEntryPrice()).isEqualByComparingTo(STORY_ENTRY_PRICE);
			assertThat(plan.getStopLossPrice())
				.isEqualByComparingTo(STORY_ENTRY_PRICE.multiply(new BigDecimal("0.98")));
			assertThat(plan.getTakeProfitPrice())
				.isEqualByComparingTo(STORY_ENTRY_PRICE.multiply(new BigDecimal("1.08")));
			// 042 EXITPRESET-005 승계 — 사용자 주도 예약도 실행 세대에 귀속된다.
			assertThat(plan.getPracticeAttemptRunNumber()).isEqualTo(1L);
		});
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);

		// write-once — 같은 진입에서 두 번째 생성은 거부된다.
		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("5"), new BigDecimal("3"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_ALREADY_EXISTS));
	}

	// 2단계 대본에서는 사용자 주도 예약도 열리지 않는다(049 ORDERBASICS-022를 052가 그대로 승계) —
	// 자동만 끄고 수동을 열어 두면 그 대본에서 예약이 다른 문으로 되살아난다.
	@Test
	void orderBasicsRunRejectsAUserDrivenReservationToo() {
		Fixture fixture = orderBasicsRun("ob-manual-blocked");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("3"), new BigDecimal("5"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		assertThat(exitPlans(fixture)).isEmpty();
	}

	// 보유가 없으면 예약할 대상이 없다 — 매수 전 예약 시도는 거부된다(052 EXITFREE-020).
	@Test
	void storyScriptRejectsAReservationWhileNothingIsHeld() {
		Fixture fixture = storyRun("ob-nothing-held");

		clock.set(BASE_NOW.plusSeconds(1));
		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("3"), new BigDecimal("5"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));
		assertThat(exitPlans(fixture)).isEmpty();
	}

	/**
	 * <b>짧게 돌리면 회귀를 놓친다.</b> 기본 프리셋은 매수 6~9초(가상 2~3분) 만에 발동하므로, 여기서는
	 * 두 바퀴(가상 40분)를 돌려 대본이 익절선 위 112,000원과 손절선 아래 88,000원을 <b>실제로</b> 지나간
	 * 것을 진행 중 봉의 고가·저가로 증명한 뒤에도 포지션이 살아 있는지 본다. 예약이 살아 있는 구현이라면
	 * 이 지점에서 반드시 깨진다.
	 */
	@Test
	void orderBasicsRunSurvivesTwoFullCyclesOfTicksAndThenCompletesAManualRoundTrip() {
		Fixture fixture = orderBasicsRun("ob-ticks");
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		tickRounds(fixture);

		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		// 대본이 두 발동선을 실제로 지났다는 증거 — 없으면 "커서가 안 움직여서" 통과했을 수도 있다.
		assertThat(attempt.getScenarioStageId()).isEqualTo("ORDER_BASICS");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isGreaterThanOrEqualTo(60L);
		assertThat(attempt.getScenarioCandleHigh()).isGreaterThanOrEqualTo(ORDER_BASICS_HIGH);
		assertThat(attempt.getScenarioCandleLow()).isLessThanOrEqualTo(ORDER_BASICS_LOW);

		// 그런데도 자동 청산이 없다.
		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
		assertThat(riskSnapshotRepository.countByAttemptIdAndRunNumber(fixture.attemptId(), 1L)).isEqualTo(1L);
		assertThat(marketRoundTripCompleted(fixture)).isFalse();

		// 사용자가 직접 팔아야 시장가 왕복이 인정된다(#503) — 2단계의 학습 목표 그 자체다.
		clock.set(BASE_NOW.plusSeconds((long)SECONDS_PER_TICK * TICK_ROUNDS + 10L));
		sell(fixture);

		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();
	}

	/**
	 * 예약이 없어도 <b>관찰·복기가 끝까지 돈다.</b> 기준선까지 빼는 구현은 여기서
	 * {@code PRACTICE_EVIDENCE_MISSING}으로 깨진다 — 이 항목에서 가장 위험한 회귀다.
	 */
	@Test
	void orderBasicsRunKeepsObservationAndReflectionWorkingEndToEnd() {
		Fixture fixture = orderBasicsRun("ob-evidence");
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		Long holdingId = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow()
			.getId();

		tickRounds(fixture);
		createQualifyingObservations(fixture.userId(), holdingId, BASE_NOW.plusSeconds(150));

		clock.set(BASE_NOW.plusSeconds(400));
		sell(fixture);
		clock.set(BASE_NOW.plusSeconds(410));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holdingId, "2단계에서 직접 사고 팔아 본 것을 복기합니다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	// 진입별 대조 배열의 근거인 entrySequence와 프리셋 확정이 예약 부재와 무관하게 그대로인지 본다.
	//
	// 049 ORDERBASICS-015 — 프리셋 선택은 시장가·지정가 왕복을 "둘 다" 마쳐야 열린다(4번 게이트). 그래서
	// 프리셋을 고르기 전에 지정가 왕복(진입 2개째)까지 마쳐야 하고, 이 테스트가 실제로 보려는 진입은
	// 세 번째가 된다.
	@Test
	void thirdEntryInAnOrderBasicsRunGetsSequenceThreeWithTheChosenPresetAndStillNoExitPlan() {
		Fixture fixture = orderBasicsRun("ob-reentry");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);

		// 지정가 왕복 — 049 ORDERBASICS-015 게이트를 통과시킨다.
		clock.set(BASE_NOW.plusSeconds(3));
		limitOrder(fixture, OrderSide.BUY, LIMIT_BUY_PRICE);
		clock.set(BASE_NOW.plusSeconds(33));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		clock.set(BASE_NOW.plusSeconds(39));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
		clock.set(BASE_NOW.plusSeconds(40));
		limitOrder(fixture, OrderSide.SELL, LIMIT_SELL_PRICE);
		clock.set(BASE_NOW.plusSeconds(42));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();

		// 포지션이 정리됐으므로 프리셋을 고를 수 있다 — 고른 값이 다음 진입의 기준선에 확정되어야 한다.
		attemptService.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED);
		clock.set(BASE_NOW.plusSeconds(43));
		buy(fixture);

		PracticeRiskSnapshot third = latestSnapshot(fixture);
		assertThat(third.getEntrySequence()).isEqualTo(3);
		assertThat(third.getExitPreset()).isEqualTo(ExitPreset.RELAXED);
		assertThat(riskSnapshotRepository.countByAttemptIdAndRunNumber(fixture.attemptId(), 1L)).isEqualTo(3L);
		assertThat(exitPlans(fixture)).isEmpty();
	}

	private void limitOrder(Fixture fixture, OrderSide side, BigDecimal limitPrice) {
		limitOrderService.createLimitOrder(
			fixture.userId(), "ob-reentry-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), side, QUANTITY, limitPrice));
	}

	private void tickRounds(Fixture fixture) {
		for (int round = 1; round <= TICK_ROUNDS; round++) {
			clock.set(BASE_NOW.plusSeconds((long)SECONDS_PER_TICK * round));
			chartService.tick(fixture.userId(), Market.CRYPTO);
		}
	}

	private void createQualifyingObservations(Long userId, Long holdingId, LocalDateTime firstAt) {
		clock.set(firstAt);
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(1));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(2));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private boolean marketRoundTripCompleted(Fixture fixture) {
		return stageProgressCalculationService
			.calculate(attemptRepository.findById(fixture.attemptId()).orElseThrow())
			.marketBuySellCompleted();
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private PracticeRiskSnapshot latestSnapshot(Fixture fixture) {
		return riskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(fixture.attemptId(), 1L)
			.orElseThrow();
	}

	private List<ExitPlan> exitPlans(Fixture fixture) {
		return exitPlanRepository.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L);
	}

	private BigDecimal reservedQuantity(Fixture fixture) {
		return holdingRepository.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getReservedQuantity)
			.orElse(BigDecimal.ZERO);
	}

	private Fixture orderBasicsRun(String scenario) {
		return tutorialRun(
			scenario, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1, "ORDER_BASICS", ORDER_BASICS_ENTRY_PRICE);
	}

	private Fixture storyRun(String scenario) {
		return tutorialRun(scenario, TutorialScenarioScriptId.CRYPTO_STORY_V1, "ACT2_RUMOR", STORY_ENTRY_PRICE);
	}

	/**
	 * <b>서비스 경로로는 2단계 실행을 만들 수 없다</b> — 진입 대본이 041에 고정돼 있고 전환 엔드포인트는
	 * 049 tasks 5번이다. 그래서 종목 선택까지는 서비스로 하고 대본 식별자만 직접 박는다. 커서도 직접
	 * 세운다 — 이 테스트의 대상은 대본 저작이 아니라 예약 원장이다.
	 */
	private Fixture tutorialRun(
		String scenario, TutorialScenarioScriptId scriptId, String stageId, BigDecimal openPrice) {
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
		ReflectionTestUtils.setField(attempt, "scenarioScriptId", scriptId);
		attempt.startScenarioProgress(stageId, openPrice, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		assertThat(attempt.scenarioScriptId()).isEqualTo(scriptId);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
