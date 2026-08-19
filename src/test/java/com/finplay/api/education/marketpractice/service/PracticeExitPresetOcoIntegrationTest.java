// 자동 OCO 예약이 tick에서 체결된 뒤 실행 세대 원장·재진입·재시작이 이어지는지 실제 MySQL로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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
 * 042 plan 테스트 전략이 "mock만으로 끝내지 않는다 — 예약 수량은 holdings.reserved_quantity 원장이 얽히므로
 * 통합 테스트가 정본이다"라고 못박은 자리다.
 *
 * <p><b>이 테스트가 잡는 회귀가 구체적으로 있다.</b> OCO 체결이 만드는 매도 주문에 attempt 귀속이 없으면
 * 042의 판정이 전부 그 매도를 못 본다 — 순보유수량이 매수분 그대로 남아 프리셋이 영구 잠기고, 재매수에
 * 새 기준선·새 예약이 생기지 않고, 매도 원인이 항상 null이 되고, 재시작이 영구히 409가 된다.
 * <b>단위 테스트는 전부 초록인 채로 그 상태를 통과시킨다.</b>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeExitPresetOcoIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	// 2막-a 루머 구간의 분별 가격은 10180 / 10147.91 / 10097.80 / 10020.50 / 9941.58 / 9895.18 / 9807.62 /
	// 9750이다. 0분에 진입하면 BALANCED(-3%) 손절선이 10180 * 0.97 = 9874.60이고, 6번째 분(9807.62)에서
	// 처음 그 아래로 내려간다.
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("10180.00000000");
	private static final BigDecimal BALANCED_STOP_LOSS = new BigDecimal("9874.60000000");

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
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeAttemptRestartService restartService;
	@Autowired
	private TradeService tradeService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void stopLossFillKeepsTheRunLedgerConsistentAndLetsTheUserReenterAndRestart() {
		Fixture fixture = tutorialRunAtRumorStage("oco-stop");

		// 매수 — 같은 트랜잭션에서 기준선과 예약이 함께 생긴다(EXITPRESET-012).
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		PracticeRiskSnapshot firstEntry = latestSnapshot(fixture);
		assertThat(firstEntry.getEntrySequence()).isEqualTo(1);
		assertThat(firstEntry.getExitPreset()).isEqualTo(ExitPreset.BALANCED);
		assertThat(firstEntry.getEntryPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(firstEntry.getStopLossPrice()).isEqualByComparingTo(BALANCED_STOP_LOSS);

		ExitPlan reservation = onlyExitPlan(fixture);
		assertThat(reservation.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		// 예약 기준가는 대본 canonical price다 — 엔진 기본 경로의 사인파 항시 시세가 아니다.
		assertThat(reservation.getBaselinePrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);
		// 보유 중에는 프리셋을 바꿀 수 없다(EXITPRESET-003).
		assertThatThrownBy(() -> attemptService.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));

		// tick — 루머 구간을 23초 흘려 6번째 분에서 손절선을 지난다.
		clock.set(BASE_NOW.plusSeconds(23));
		chartService.tick(fixture.userId(), Market.CRYPTO);

		ExitPlan filled = onlyExitPlan(fixture);
		assertThat(filled.getStatus()).isEqualTo(ExitPlanStatus.FILLED_STOP_LOSS);
		assertThat(filled.getTriggeredOrder()).isNotNull();
		// **회귀 방어의 핵심** — 자동 청산 매도가 실행 세대에 귀속돼야 아래가 전부 성립한다.
		assertThat(filled.getTriggeredOrder().getPracticeAttemptId()).isEqualTo(fixture.attemptId());
		assertThat(filled.getTriggeredOrder().getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);

		// 포지션이 정리됐으므로 프리셋을 다시 고를 수 있다(EXITPRESET-003의 "재진입 대기 중에는 다시 허용").
		PracticeAttemptResponse afterStop = attemptService
			.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED);
		assertThat(afterStop.exitPresetLocked()).isFalse();
		assertThat(afterStop.selectedExitPreset()).isEqualTo("RELAXED");

		// 재매수 — 새 진입이라 새 기준선과 새 예약이 바뀐 프리셋으로 생긴다(EXITPRESET-017).
		clock.set(BASE_NOW.plusSeconds(30));
		buy(fixture);

		PracticeRiskSnapshot secondEntry = latestSnapshot(fixture);
		assertThat(secondEntry.getEntrySequence()).isEqualTo(2);
		assertThat(secondEntry.getExitPreset()).isEqualTo(ExitPreset.RELAXED);
		assertThat(exitPlanRepository.findPendingPracticeRunExitPlanIds(fixture.attemptId(), 1L)).hasSize(1);

		// 재시작 — 예약 취소가 주문 취소보다 먼저라 보상 매도가 성공한다(EXITPRESET-015).
		clock.set(BASE_NOW.plusSeconds(40));
		PracticeAttemptResponse restarted = restartService.restart(fixture.userId(), Market.CRYPTO);
		assertThat(restarted.runNumber()).isEqualTo(2L);
		assertThat(exitPlanRepository.findPendingPracticeRunExitPlanIds(fixture.attemptId(), 1L)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// EXITPRESET-016 — 전량 예약 상태에서도 사용자가 직접 팔 수 있어야 한다.
	@Test
	void manualMarketSellSucceedsWhileTheWholeQuantityIsReserved() {
		Fixture fixture = tutorialRunAtRumorStage("oco-manual");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), "manual-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));

		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "oco-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private PracticeRiskSnapshot latestSnapshot(Fixture fixture) {
		return riskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(fixture.attemptId(), 1L)
			.orElseThrow();
	}

	private ExitPlan onlyExitPlan(Fixture fixture) {
		List<ExitPlan> plans = exitPlanRepository
			.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L);
		assertThat(plans).hasSize(1);
		return plans.get(0);
	}

	private BigDecimal reservedQuantity(Fixture fixture) {
		return holdingRepository.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getReservedQuantity)
			.orElse(BigDecimal.ZERO);
	}

	// 대기 구간을 이미 지나 2막-a 루머 0분에 서 있는 실행을 만든다 — 이 테스트의 대상은 대본 저작이 아니라
	// 예약 원장이므로 커서를 직접 세운다.
	private Fixture tutorialRunAtRumorStage(String scenario) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + suffix, scenario, BigDecimal.ONE, 0L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		attempt.startScenarioProgress("ACT2_RUMOR", ENTRY_PRICE, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
