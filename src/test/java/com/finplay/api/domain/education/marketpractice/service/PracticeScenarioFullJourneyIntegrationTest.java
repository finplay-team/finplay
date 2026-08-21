// 0막 대기부터 완료까지 대본 한 편을 실제 MySQL로 완주시켜 사건 노출·진입별 대조·프리셋 분기를 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * 041 tasks 7번. <b>단위 테스트로는 이 자리를 대신할 수 없다</b> — 지난 세 PR의 차단급 결함 넷이 전부
 * 통합에서만 드러났고(대본 없는 시장의 전면 장애, 실행 세대를 넘어 누적되는 보유 수량, 귀속 없는 자동 청산
 * 매도, 실시간 시세로 오체결되는 튜토리얼 예약) 그때 단위 테스트는 모두 초록이었다.
 *
 * <p>완주 경로는 <b>0막 대기 → 매수 → 1막 → 2막 손절 → 확정 하락 관전 → 재진입 대기 → 재매수 →
 * 3막 익절 → 4막 관전 → 복기 → 완료</b>이며, 그 사이 사건 공개 게이트와 진입별 대조 배열을 함께 본다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeScenarioFullJourneyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	// 재진입 매수는 대본이 0.87배까지 내려간 뒤라 수량이 작으면 종목 최소 주문금액(5,000원)에 걸린다.
	private static final BigDecimal QUANTITY = new BigDecimal("1.00000000");
	// 한 tick이 소비하는 최대 실제 초 = 가상 10분(MAX_TICK_GAP_SECONDS). 이보다 벌리면 clamp돼 시간이 버려진다.
	private static final int TICK_SECONDS = 30;
	private static final int MAX_TICKS = 60;
	// 2막-a 루머의 저점(기준가 10000 × 0.975). CAUTIOUS(-2%) 손절선은 이 위, BALANCED(-3%)는 이 아래라
	// 두 프리셋의 손절 구간이 갈린다(041 plan §프리셋 도달 조건 검증).
	private static final BigDecimal RUMOR_LOW = new BigDecimal("9750");
	// 대본 종점(ACT4_CRASH 마지막 분, 10000 × 0.79) — 완료 응답의 "안 팔았다면" 기준가다.
	private static final BigDecimal SCRIPT_FINAL_PRICE = new BigDecimal("7900.00000000");

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
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private TestClock clock;

	private LocalDateTime now = BASE_NOW;

	@BeforeEach
	void setUp() {
		now = BASE_NOW;
		clock.set(now);
	}

	@Test
	void cautiousRunStopsInTheRumorThenReentersTakesProfitInActThreeAndCompletesWithTwoEntries() {
		Fixture fixture = tutorialRun("full-journey");
		setExitPreset(fixture, ExitPreset.CAUTIOUS);

		// --- 0막 대기: 매수하지 않으면 대본이 진행하지 않고 사건도 열리지 않는다 ---
		tick(fixture);
		PracticeTutorialChartResponse idle = tick(fixture);
		assertThat(idle.scenarioStage()).isEqualTo("IDLE_ENTRY");
		assertThat(idle.scenarioProgressing()).isFalse();
		assertThat(idle.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(idle.revealedEvents()).isEmpty();

		// --- 매수 → 1막 ---
		buy(fixture);
		PracticeRiskSnapshot firstEntry = snapshot(fixture, 1);
		assertThat(firstEntry.getExitPreset()).isEqualTo(ExitPreset.CAUTIOUS);

		// **공개 게이트.** 1막 호재는 영향이 이미 가격에 들어간 뒤에도 공개 분(영향 시작 +4분)에 닿기 전에는
		// 나오지 않는다. 여기서만 tick을 잘게 쓴다 — 30초(가상 10분)씩 밀면 공개 분을 지나쳐 이 경계를 볼 수
		// 없다.
		PracticeTutorialChartResponse beforeReveal = tick(fixture, 9);
		assertThat(beforeReveal.scenarioStage()).isEqualTo("ACT1");
		assertThat(beforeReveal.scenarioProgressing()).isTrue();
		assertThat(beforeReveal.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(beforeReveal.revealedEvents()).isEmpty();

		PracticeTutorialChartResponse afterReveal = tick(fixture, 9);
		assertThat(afterReveal.revealedEvents()).hasSize(1);
		assertThat(afterReveal.revealedEvents().get(0).stage()).isEqualTo("ACT1");
		assertThat(afterReveal.revealedEvents().get(0).headline()).startsWith("[연습]");
		assertThat(afterReveal.causeStatus()).isEqualTo("REVEALED");

		Long holdingId = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getId)
			.orElseThrow();
		// 관찰 evidence는 실행 세대 단위다 — 아래 재매수 뒤에도 사라지지 않아야 한다(SCENARIO-019a).
		// 시간 분산 관찰(2분 이상 3회)을 만들려고 벽시계를 대본보다 크게 민다 — 초과분은 clamp돼 대본이
		// 건너뛰지 않는다(MAX_TICK_GAP_SECONDS).
		observe(fixture, holdingId);
		tick(fixture, 70);
		observe(fixture, holdingId);
		tick(fixture, 70);
		observe(fixture, holdingId);

		// --- 2막 손절 ---
		tickUntil(fixture, () -> plans(fixture).get(0).getStatus() == ExitPlanStatus.FILLED_STOP_LOSS);
		InvestmentPracticeResponse afterStop = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(afterStop.entries()).hasSize(1);
		PracticeEntryResponse stopped = afterStop.entries().get(0);
		assertThat(stopped.sellCause()).isEqualTo("STOP_LOSS");
		// **CAUTIOUS 분기** — 루머 저점에서 걸렸으므로 2막 확정의 다이빙까지 갈 필요가 없었다.
		assertThat(stopped.sellPrice()).isGreaterThanOrEqualTo(RUMOR_LOW);
		// 진행 중 기준가는 현재 대본가다 — 대본 종점을 미리 내려보내면 결말이 새어 나간다.
		assertThat(afterStop.priceAfterSell()).isNotNull().isNotEqualByComparingTo(SCRIPT_FINAL_PRICE);

		// --- 확정 하락 관전 → 재진입 대기 ---
		PracticeTutorialChartResponse reentryIdle = tickUntil(
			fixture, () -> "IDLE_REENTRY".equals(latestChart(fixture).scenarioStage()));
		assertThat(reentryIdle.scenarioProgressing()).isFalse();
		// 대기 구간에는 사건이 없다 — 2막 사건 둘은 이미 열려 있지만 "지금 구간의 원인"은 없다.
		assertThat(reentryIdle.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(reentryIdle.revealedEvents()).hasSize(3);

		// --- 재매수: 포지션이 없으니 프리셋을 다시 고를 수 있다(EXITPRESET-003) ---
		setExitPreset(fixture, ExitPreset.BALANCED);
		buy(fixture);
		assertThat(snapshot(fixture, 2).getExitPreset()).isEqualTo(ExitPreset.BALANCED);

		// --- 3막 익절 ---
		tickUntil(fixture, () -> plans(fixture).size() == 2
			&& plans(fixture).get(1).getStatus() == ExitPlanStatus.FILLED_TAKE_PROFIT);

		// --- 4막 관전 → 대본 종료 ---
		PracticeTutorialChartResponse finished = tickUntil(
			fixture, () -> "FINISHED".equals(latestChart(fixture).scenarioStage()));
		assertThat(finished.scenarioProgressing()).isFalse();
		assertThat(finished.revealedEvents()).hasSize(5);

		// --- 복기 → 완료 ---
		now = now.plusSeconds(TICK_SECONDS);
		clock.set(now);
		reflectionService.createReflection(fixture.userId(), new PracticeHoldingReflectionCreateRequest(
			holdingId, "루머에서 잘렸고 재진입해 익절했다. 끝까지 들고 있었으면 어땠을지 봤다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		// 관찰 evidence가 재매수를 건너 살아남았다(SCENARIO-019a) — 아니면 복기가 409로 막혔을 것이다.
		assertThat(completed.steps().get(2).status()).isEqualTo("COMPLETED");

		// **이 PR이 닫는 결함.** 실행 전체 요약은 여전히 첫 매도(손절)만 가리키는데, 진입별 배열은 손절과
		// 익절을 둘 다 보여준다.
		assertThat(completed.steps().get(3).evidence().tradeResult().sellCause()).isEqualTo("STOP_LOSS");
		assertThat(completed.entries()).hasSize(2);
		PracticeEntryResponse entryOne = completed.entries().get(0);
		PracticeEntryResponse entryTwo = completed.entries().get(1);
		assertThat(entryOne.entrySequence()).isEqualTo(1);
		assertThat(entryOne.exitPreset()).isEqualTo("CAUTIOUS");
		assertThat(entryOne.sellCause()).isEqualTo("STOP_LOSS");
		assertThat(entryTwo.entrySequence()).isEqualTo(2);
		assertThat(entryTwo.exitPreset()).isEqualTo("BALANCED");
		assertThat(entryTwo.sellCause()).isEqualTo("TAKE_PROFIT");
		// 이슈 #503 — 두 진입 모두 시장가로 열렸다.
		assertThat(entryOne.buyOrderType()).isEqualTo("MARKET");
		assertThat(entryTwo.buyOrderType()).isEqualTo("MARKET");

		// **이 대본은 사용자가 한 번도 직접 팔지 않는다** — 두 매도 모두 예약이 발동시킨 것이다. 그래서
		// 대본을 끝까지 완주해 완료했는데도 "시장가로 사고팔기" 단계는 열리지 않는다(이슈 #503). 주문
		// 유형만 보고 판정하는 구현은 여기서 정확히 틀린다.
		assertThat(completed.tutorialStageProgress().marketBuySellCompleted()).isFalse();
		assertThat(completed.tutorialStageProgress().limitBuySellCompleted()).isFalse();
		// 프리셋은 직접 골랐다(이 대본은 CAUTIOUS로 시작해 BALANCED로 바꾼다) — 판정 기준이 "골랐는가"라
		// 중간에 프리셋을 바꿔도 통과가 취소되지 않는다.
		assertThat(completed.tutorialStageProgress().exitPresetSelected()).isTrue();

		// 완료 대조의 기준가는 대본 종점이다 — 사용자가 실제로 어디까지 갔는지와 무관하다(SCENARIO-021).
		assertThat(completed.priceAfterSell()).isEqualByComparingTo(SCRIPT_FINAL_PRICE);
		// 손절도 익절도 "안 팔았다면"보다 나았다 — 두 진입 모두 실현손익이 가상 보유보다 크다.
		assertThat(entryOne.unrealizedPnlIfHeld()).isLessThan(entryOne.realizedPnl());
		assertThat(entryTwo.unrealizedPnlIfHeld()).isLessThan(entryTwo.realizedPnl());
		assertThat(completed.revealedEvents()).hasSize(5);
	}

	/**
	 * <b>프리셋 분기가 실제로 갈리는지</b>가 이 테스트의 유일한 대상이다 — 041 plan §프리셋 도달 조건의
	 * 부등식은 대본 파일만 보고, "그래서 사용자가 언제 잘리는가"는 체결 원장으로만 확인된다.
	 *
	 * <p>{@code BALANCED}(-3%)는 루머 저점 {@code 0.975}를 버티고 2막 확정의 다이빙에서 잘린다.
	 * 위 테스트의 {@code CAUTIOUS}(-2%)가 루머에서 잘린 것과 <b>같은 대본·같은 진입가</b>에서 갈린다.
	 */
	@Test
	void balancedRunSurvivesTheRumorAndOnlyStopsInTheConfirmedDive() {
		Fixture fixture = tutorialRun("balanced-branch");
		setExitPreset(fixture, ExitPreset.BALANCED);

		tick(fixture);
		buy(fixture);
		tickUntil(fixture, () -> plans(fixture).get(0).getStatus() == ExitPlanStatus.FILLED_STOP_LOSS);

		InvestmentPracticeResponse afterStop = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		PracticeEntryResponse stopped = afterStop.entries().get(0);
		assertThat(stopped.sellCause()).isEqualTo("STOP_LOSS");
		// 루머 저점보다 아래에서 체결됐다 — 루머를 버텼다는 뜻이고, CAUTIOUS와 반대다.
		assertThat(stopped.sellPrice()).isLessThan(RUMOR_LOW);
		// 손절선 자체도 루머 저점 아래에 있다(같은 진입가에서 두 프리셋이 갈리는 근거).
		assertThat(stopped.stopLossPrice()).isLessThan(RUMOR_LOW);
	}

	private PracticeTutorialChartResponse tick(Fixture fixture) {
		return tick(fixture, TICK_SECONDS);
	}

	private PracticeTutorialChartResponse tick(Fixture fixture, int seconds) {
		now = now.plusSeconds(seconds);
		clock.set(now);
		return chartService.tick(fixture.userId(), Market.CRYPTO);
	}

	// 조건이 참이 될 때까지 tick한다. 이미 참이면 한 번도 밀지 않는다 — 한 tick이 가상 10분을 소비하므로
	// 무조건 한 번 미는 판정은 관찰하려는 지점을 지나쳐 버린다. 상한을 두는 이유는 조건이 영원히 참이 되지
	// 않는 회귀에서 테스트가 매달리지 않고 실패하게 하려는 것이다.
	private PracticeTutorialChartResponse tickUntil(Fixture fixture, BooleanSupplier condition) {
		if (condition.getAsBoolean()) {
			return latestChart(fixture);
		}
		for (int attempt = 0; attempt < MAX_TICKS; attempt++) {
			PracticeTutorialChartResponse last = tick(fixture);
			if (condition.getAsBoolean()) {
				return last;
			}
		}
		throw new AssertionError("대본이 " + MAX_TICKS + "번의 tick 안에 조건에 도달하지 않았습니다.");
	}

	private PracticeTutorialChartResponse latestChart(Fixture fixture) {
		return chartService.getChart(fixture.userId(), Market.CRYPTO);
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "journey-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	/**
	 * 049 ORDERBASICS-015 — {@code PracticeAttemptService.selectExitPreset}은 이제 시장가·지정가 왕복을
	 * 요구한다. 이 테스트들의 대상은 그 게이트가 아니라 "프리셋에 따라 손절·익절 분기가 실제로 갈리는가"
	 * (041)이므로, 왕복 전제를 만드는 워밍업 주문을 끼워 넣는 대신 엔티티를 직접 조작해 프리셋만 정한다.
	 * 워밍업 주문을 끼워 넣으면 이 실행의 "첫 매도"({@code TradeService.summarizePracticeRun}이
	 * 반환하는 {@code firstSellTrade}) 자체가 바뀌어 손절 원인 대조(sellCause)가 통째로 어긋난다.
	 */
	private void setExitPreset(Fixture fixture, ExitPreset preset) {
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(fixture.userId(), Market.CRYPTO)
			.orElseThrow();
		attempt.selectExitPreset(preset, now);
		attemptRepository.saveAndFlush(attempt);
	}

	private void observe(Fixture fixture, Long holdingId) {
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private PracticeRiskSnapshot snapshot(Fixture fixture, int entrySequence) {
		return riskSnapshotRepository
			.findByAttemptIdAndRunNumberAndEntrySequence(fixture.attemptId(), 1L, entrySequence)
			.orElseThrow();
	}

	// 예약은 진입 순서대로 생기므로 id 오름차순이 곧 진입 순서다.
	private List<ExitPlan> plans(Fixture fixture) {
		return exitPlanRepository
			.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L)
			.stream()
			.sorted(Comparator.comparing(ExitPlan::getId))
			.toList();
	}

	// 커서를 미리 세우지 않는다 — 첫 tick이 대본의 첫 구간 0분으로 초기화하는 것까지 이 테스트의 대상이다.
	private Fixture tutorialRun(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = instrumentRepository
			.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_1")
			.orElseThrow();

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		// 049 tasks 5번 이후 진입 대본은 2단계(CRYPTO_ORDER_BASICS_V1)로 열린다. 이 테스트가 검증하는
		// 것은 041 대본(사건 공개·OCO 손절익절)의 동작이므로 041로 전환한다 — 전환 엔드포인트가 쓰는
		// 것과 같은 엔티티 메서드를 그대로 쓴다. 커서 다섯 컬럼은 건드리지 않으므로(모두 null) "첫 tick이
		// 첫 구간 0분으로 초기화한다"는 이 테스트의 전제는 그대로 유지된다.
		attempt.advanceScenarioScript(
			com.finplay.api.domain.market.entity.TutorialScenarioScriptId.CRYPTO_STORY_V1, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
