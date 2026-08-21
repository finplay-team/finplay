// 대본 커서 전진의 상태 전이표 3행·clamp·초 단위 누적·건너뛴 분 순차 정산을 시각 주입으로 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class PracticeScenarioProgressServiceTest {

	private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 8, 19, 12, 0);
	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");

	private final PracticeOrderSettlementService settlementService = mock(PracticeOrderSettlementService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = new PracticeAttemptCanonicalPriceService(
		mock(PracticeAttemptRepository.class),
		new TutorialPriceGenerator(),
		new TutorialScenarioScriptLoader(new ObjectMapper()));
	private final PracticeScenarioProgressService service = new PracticeScenarioProgressService(
		canonicalPriceService, settlementService, tradeService);

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

	// PR #494 QA 참고 3 — 종목 선택 직후 매수하고 첫 tick을 부르면, 초기화 tick이 커서만 세우고 끝나 화면이
	// 한 사이클 동안 "대기 중"으로 보였다. 이동은 시간을 소비하지 않으므로 같은 tick에서 나가야 한다.
	@Test
	void firstTickLeavesTheIdleLoopImmediatelyWhenTheUserAlreadyBought() {
		PracticeAttempt attempt = scenarioAttempt();
		holdQuantity("0.5");

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		// 시간은 소비하지 않았다 — 진행 기준 시각이 그대로여야 다음 tick의 delta가 줄지 않는다.
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR);
	}

	// 미보유면 초기화 tick은 지금까지처럼 첫 구간에 머문다(위 테스트의 반증 방어).
	@Test
	void firstTickStaysInTheIdleLoopWhenNothingIsHeld() {
		PracticeAttempt attempt = scenarioAttempt();

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
	}

	// 대기 구간 끝에 닿아 0으로 되감는 지점에서 체결되면 남은 delta가 0이라 순회가 먼저 끝난다 — 그때도 이
	// tick 안에서 진행 구간으로 나간다(041 4~5번 2차 리뷰가 6번으로 넘긴 항목).
	@Test
	void tickThatFillsAtTheLoopRewindPointStillLeavesTheIdleLoopInTheSameTick() {
		// IDLE_ENTRY는 20분(60초)이다. 57초에서 3초를 밀면 되감기 지점에 정확히 닿고 남은 delta가 0이 된다.
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 57L, ANCHOR);
		// **체결이 순회 도중에 생기는 상황을 재현한다.** 처음부터 보유를 두면 순회 첫 판정에서 곧바로
		// 탈출해 이 결함이 있던 경로를 지나지 않는다 — 되감기 지점의 정산이 체결을 만들어야 재현된다.
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("0.5"));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
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

	// 순회 도중에 지정가 매수가 체결돼 대기 구간을 벗어나는 경로다. 대기 구간에 걸어 둔 지정가는 주
	// 사용자 경로이고, 이 경로에서만 `consumed`가 `step`과 달라진다.
	@Test
	void buyFilledMidTraversalLeavesTheIdleLoopWithinTheSameTick() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		// 순회 시작에 1회, 진입한 가상 분마다 1회 조회한다 — 두 번째 조회(1분 진입 직후)에서 보유가 생긴다.
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("1"));
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		// 다음 tick으로 밀리지 않고 이번 tick에서 1막 0분에 선다.
		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		// 최종 커서만 보면 "순회 시작 시점에 이미 보유" 경로와 구별되지 않는다 — 대기 구간에서 한 번
		// 정산한 뒤 진행 구간에서 다시 정산했다는 사실이 이 경로를 특정한다.
		verify(settlementService, times(2)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), any(LocalDateTime.class),
			any(BigDecimal.class));
	}

	// 소비하지 않은 초가 차감되지 않아야 한다 — 대기 구간을 벗어나며 버린 시간은 다음 tick에 되살아나지
	// 않지만, 체결 이후 남은 시간은 새 구간에서 그대로 쓰여야 한다.
	@Test
	void secondsLeftAfterTheMidTraversalFillAreSpentInTheNextProgressStage() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("1"));
		// 3초 지점에 체결되고 9초에 tick이 왔다 — 체결 이후 6초가 1막에서 쓰인다.
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(9));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(6L);
		ArgumentCaptor<LocalDateTime> pricedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(settlementService, times(4)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), pricedAt.capture(),
			any(BigDecimal.class));
		// 대기 구간 1분(체결) → 진행 구간 0·1·2분. 첫 두 건이 같은 시각인 것은 이동이 시간을 소비하지
		// 않기 때문이고, 이 시퀀스가 "순회 도중 체결"을 다른 경로와 갈라 놓는다.
		assertThat(pricedAt.getAllValues()).containsExactly(
			ANCHOR.plusSeconds(3), ANCHOR.plusSeconds(3), ANCHOR.plusSeconds(6), ANCHOR.plusSeconds(9));
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
		// 2막 확정 하락 도중 손절돼 보유가 0이 된다 — 초판에는 여기서 재진입 대기로 순간이동하는 네 번째
		// 행이 있었고, 그것이 손절한 사용자에게서 확정 하락 관전을 빼앗았다.
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO);

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

		ArgumentCaptor<LocalDateTime> pricedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(settlementService, times(10)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), pricedAt.capture(),
			any(BigDecimal.class));
		// 정산 시각은 순회 순서대로 엄격히 증가하고 [직전 tick, 이번 tick] 안에 든다 — 뒤섞이면 지정가가
		// 미래 가격으로 체결된 것처럼 원장에 남는다.
		assertThat(pricedAt.getAllValues()).isSorted().doesNotHaveDuplicates()
			.allSatisfy(at -> assertThat(at).isBetween(ANCHOR, ANCHOR.plusSeconds(30)));
		assertThat(pricedAt.getAllValues().get(9)).isEqualTo(ANCHOR.plusSeconds(30));
	}

	// 세 리뷰어가 함께 찾은 결함의 회귀 방어 — 대본이 끝난 뒤의 tick은 새 가상 분에 진입하지 않으므로
	// 정산 호출점이 사라진다. 버전 1은 tick마다 무조건 정산했고, 그 보장을 잃으면 종료 후 접수한 지정가가
	// 조건을 만족해도 영구히 PENDING으로 남는다.
	@Test
	void tickAfterTheScriptFinishedStillSettlesOnce() {
		PracticeAttempt attempt = startedAt("ACT4_CRASH", 60L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		verify(settlementService, times(1))
			.settleCurrentRun(eq(ATTEMPT_ID), eq(1L), eq(ANCHOR.plusSeconds(30)), any(BigDecimal.class));
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(60L);
	}

	// 같은 초에 두 번 온 tick도 정산은 한다(가상 분 진입이 없다).
	@Test
	void tickWithoutElapsedTimeStillSettlesOnce() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR);

		verify(settlementService, times(1)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), eq(ANCHOR), any(BigDecimal.class));
	}

	// 순회가 지나간 모든 가상 분의 극값이 진행 중 봉에 담긴다 — 지나온 경로를 복원할 수 없으므로 누적한다.
	@Test
	void candleHighAndLowAccumulateAcrossSkippedMinutes() {
		PracticeAttempt attempt = startedAt("ACT2_RUMOR", 0L, ANCHOR);

		// 루머 8분(24초)을 다 쓰고 속임수 반등 2분까지 들어간다 — 종점 가격이 저점보다 위라 "종점 하나만
		// 반영"하는 구현으로는 아래 저가 단정이 통과하지 못한다.
		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_FAKEOUT");
		assertThat(attempt.getScenarioCandleLow()).isEqualByComparingTo(new BigDecimal("9750.00000000"));
		assertThat(attempt.getScenarioCandleHigh()).isEqualByComparingTo(new BigDecimal("10147.91000000"));
		// 종점 가격(속임수 반등 2분 = 9888.41)은 저가·고가 어느 쪽과도 다르다.
		assertThat(canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(30)))
			.isEqualByComparingTo(new BigDecimal("9888.41000000"));
	}

	@Test
	void progressStageRollsOverToTheNextStage() {
		// 1막 15분 = 45초. 마지막 3초를 남기고 시작해 6초를 흘리면 2막 첫 분에 들어간다.
		PracticeAttempt attempt = startedAt("ACT1_RISE", 42L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(6));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	// 대본을 편집해 구간을 짧게 줄이면 이미 영속된 커서가 새 길이를 넘는다. 방어(step을 0으로 clamp)가
	// 없으면 `remaining -= consumed`가 덧셈이 되어 그 tick이 30초 상한을 넘겨 대본을 앞당긴다.
	// 루머 구간은 8분(24초)인데 커서를 30초에 세워 "구간이 줄어든 뒤" 상태를 만든다.
	@Test
	void cursorBeyondAShortenedStageIsCleanedUpWithoutInflatingRemainingTime() {
		PracticeAttempt attempt = startedAt("ACT2_RUMOR", 30L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		// 다음 구간으로 정리되고, 소비한 시간은 정확히 3초다(방어가 없으면 9초를 소비해 6초 앞선다).
		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_FAKEOUT");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR.plusSeconds(3));
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
		verify(settlementService, never()).settleCurrentRun(anyLong(), anyLong(), any(LocalDateTime.class),
			any(BigDecimal.class));
	}

	private void holdNothing() {
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);
	}

	private void holdQuantity(String quantity) {
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
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
			instrument, ANCHOR, ANCHOR.toLocalDate(), 123_456_789L, generatorVersion, null, ANCHOR);
		return attempt;
	}
}
