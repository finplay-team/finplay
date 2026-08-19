// 대본 커서 전진의 상태 전이표 3행·clamp·초 단위 누적·건너뛴 분 순차 정산을 시각 주입으로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class PracticeScenarioProgressServiceTest {

	private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 8, 19, 12, 0);
	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");

	private final PracticeOrderSettlementService settlementService = mock(PracticeOrderSettlementService.class);
	private final HoldingService holdingService = mock(HoldingService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = new PracticeAttemptCanonicalPriceService(
		mock(PracticeAttemptRepository.class),
		new TutorialPriceGenerator(),
		new TutorialScenarioScriptLoader(new ObjectMapper()));
	private final PracticeScenarioProgressService service = new PracticeScenarioProgressService(
		canonicalPriceService, settlementService, holdingService, tradeService);

	@BeforeEach
	void setUp() {
		holdNothing();
		when(tradeService.findLatestPracticeRunBuyExecutedAt(anyLong(), anyLong())).thenReturn(Optional.empty());
	}

	@Test
	void firstTickInitializesCursorToFirstStageAndOpensCandle() {
		PracticeAttempt attempt = scenarioAttempt();

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		assertThat(attempt.getScenarioCandleOpen()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioCandleHigh()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioCandleLow()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR);
	}

	// 표 1행 — 미보유 대기 구간은 벽시계로 진행하고 구간 끝에 닿으면 0으로 되감는다. 가격은 계속 움직인다.
	@Test
	void idleLoopAdvancesWithoutHoldingAndRewindsAtTheEnd() {
		// 0막 대기는 20 가상 분 = 60초다. 마지막 3초를 흘리면 구간 끝에 닿아 0으로 되감긴다.
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 57L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	// 표 2행 — 이 전이가 없으면 사용자는 매수해도 0막을 영원히 돈다.
	@Test
	void buyingInsideIdleLoopJumpsToTheZeroMinuteOfTheNextProgressStage() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 15L, ANCHOR);
		holdQuantity("3");
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	// 재진입 대기에서도 같은 전이가 성립해야 3막이 시작된다.
	@Test
	void buyingInsideReentryIdleLoopJumpsToTheThirdAct() {
		PracticeAttempt attempt = startedAt("IDLE_REENTRY", 9L, ANCHOR);
		holdQuantity("2");
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT3_REBOUND");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	// 대기 탈출 시 남은 delta를 전부 이월하면 매수 직후 첫 화면이 1막 한참 뒤가 되어 가격이 튄다.
	@Test
	void escapingIdleLoopTruncatesDeltaToTheFillInstant() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		holdQuantity("1");
		// 마지막 tick 이후 27초에 체결되고 30초에 tick이 왔다 — 3초만 1막에서 소비해야 한다.
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(27)));

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	// 표 3행 — 진행 구간은 보유 여부와 무관하게 진행한다. 4막을 관전 중인 미보유 사용자도 이 행이다.
	@Test
	void progressStageAdvancesWithoutHolding() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(9));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(9L);
	}

	// SCENARIO-010 — 매도는 상태 전이표 어느 행에도 없다. 2막에서 보유가 0이 되어도 재진입 대기로
	// 순간이동하지 않고 커서가 순서대로 확정 하락을 끝까지 지난다.
	@Test
	void sellingDoesNotMoveTheCursorToTheReentryIdleStage() {
		PracticeAttempt attempt = startedAt("ACT2_CONFIRM", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_CONFIRM");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(30L);
	}

	// 탭을 닫았다 돌아온 사용자가 그 사이 시간을 통째로 소비하지 않게 한다.
	@Test
	void tickGapIsClampedToThirtySeconds() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusMinutes(10));

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(30L);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR.plusMinutes(10));
	}

	// 초가 아니라 분으로 누적하면 2초 간격 tick에서 매번 2/3 = 0이 되어 대본이 영영 진행하지 않는다.
	@Test
	void twoSecondTicksAccumulateWithoutDrift() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		for (int index = 1; index <= 6; index++) {
			service.advance(attempt, ANCHOR.plusSeconds(2L * index));
		}

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(12L);
	}

	// 1초 미만 나머지를 매 tick 버리면 3초의 배수가 아닌 간격에서 대본이 조금씩 느려진다.
	@Test
	void subSecondRemainderCarriesToTheNextTick() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3).plusNanos(500_000_000L));
		service.advance(attempt, ANCHOR.plusSeconds(7));

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(7L);
	}

	// SCENARIO-013 — tick 종점 가격 하나로만 판정하면 그 사이 가상 분의 극값을 못 본다.
	@Test
	void everySkippedVirtualMinuteIsSettledInOrder() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		verify(settlementService, times(10)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), any(LocalDateTime.class));
	}

	// 순회가 지나간 모든 가상 분의 극값이 진행 중 봉에 담긴다 — 지나온 경로를 복원할 수 없으므로 누적한다.
	@Test
	void candleHighAndLowAccumulateAcrossSkippedMinutes() {
		PracticeAttempt attempt = startedAt("ACT2_RUMOR", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(24));

		// 루머 구간의 저점 배율 0.975가 저가로 남는다(구간 끝 값이자 최저값).
		assertThat(attempt.getScenarioCandleLow())
			.isEqualByComparingTo(CRYPTO_BASE_PRICE.multiply(new BigDecimal("0.975")));
		assertThat(attempt.getScenarioCandleHigh()).isGreaterThanOrEqualTo(attempt.getScenarioCandleOpen());
	}

	@Test
	void progressStageRollsOverToTheNextStage() {
		// 1막 15분 = 45초. 마지막 3초를 남기고 시작해 6초를 흘리면 2막 첫 분에 들어간다.
		PracticeAttempt attempt = startedAt("ACT1_RISE", 42L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(6));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	// 표 4행 — 마지막 구간 끝에서는 더 진행하지 않고 마지막 가격을 유지한다.
	@Test
	void lastStageStopsAtFinishedAndKeepsTheFinalPrice() {
		PracticeAttempt attempt = startedAt("ACT4_CRASH", 57L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));
		BigDecimal finalPrice = canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(30));
		service.advance(attempt, ANCHOR.plusSeconds(60));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT4_CRASH");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(60L);
		assertThat(canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(60)))
			.isEqualByComparingTo(finalPrice)
			.isEqualByComparingTo(CRYPTO_BASE_PRICE.multiply(new BigDecimal("0.790")));
	}

	// 생성기 버전 1 attempt는 대본을 쓰지 않는다 — 진행 계산이 아무것도 건드리지 않아야 한다.
	@Test
	void versionOneAttemptIsUntouched() {
		PracticeAttempt attempt = legacyAttempt();

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isNull();
		verify(settlementService, never()).settleCurrentRun(anyLong(), anyLong(), any(LocalDateTime.class));
	}

	private void holdNothing() {
		when(holdingService.findNetQuantity(USER_ID, Market.CRYPTO, INSTRUMENT_ID)).thenReturn(BigDecimal.ZERO);
	}

	private void holdQuantity(String quantity) {
		when(holdingService.findNetQuantity(USER_ID, Market.CRYPTO, INSTRUMENT_ID))
			.thenReturn(new BigDecimal(quantity));
	}

	private PracticeAttempt startedAt(String stageId, long elapsedSeconds, LocalDateTime progressUpdatedAt) {
		PracticeAttempt attempt = scenarioAttempt();
		attempt.startScenarioProgress(stageId, CRYPTO_BASE_PRICE, progressUpdatedAt);
		attempt.moveScenarioCursor(stageId, elapsedSeconds);
		return attempt;
	}

	private PracticeAttempt scenarioAttempt() {
		return attempt(TutorialPriceGenerator.VERSION_2);
	}

	private PracticeAttempt legacyAttempt() {
		return attempt(TutorialPriceGenerator.VERSION_1);
	}

	private PracticeAttempt attempt(short generatorVersion) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, ANCHOR.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, ANCHOR);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, ANCHOR, ANCHOR.toLocalDate(), 123_456_789L, generatorVersion, ANCHOR);
		return attempt;
	}
}
