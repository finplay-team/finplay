// 진입별 대조 배열의 매도 원인 분리와 "안 팔았다면" 평가손익 산술을 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeEntryComparisonServiceTest {

	private static final Long ATTEMPT_ID = 11L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0);

	private final PracticeRiskSnapshotRepository snapshotRepository = mock(PracticeRiskSnapshotRepository.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeExitPlanQueryService exitPlanQueryService = mock(PracticeExitPlanQueryService.class);
	private final PracticeEntryComparisonService service = new PracticeEntryComparisonService(
		snapshotRepository, tradeService, exitPlanQueryService);

	// **이 PR이 닫는 결함.** 2막 손절 → 3막 익절한 사용자의 완료 화면이 손절 하나만 가리키던 것을 진입 둘로
	// 가른다. 실행 전체 합(tradeResult)은 여전히 첫 매도 기준이므로 이 배열이 유일한 창구다.
	@Test
	void reentryRunSeparatesStopLossAndTakeProfitIntoTwoEntries() {
		PracticeAttempt attempt = attempt();
		Trade stopLossSell = sellTrade(102L, 501L, NOW.minusMinutes(10));
		Trade takeProfitSell = sellTrade(104L, 502L, NOW.minusMinutes(2));
		PracticeRiskSnapshot first = snapshot(attempt, 1, ExitPreset.CAUTIOUS, buyTrade(101L), "9700", "10500");
		// 두 진입의 주문 유형을 일부러 다르게 둔다 — 배열 전체에 한 값을 복사해 붙이는 구현이면 여기서 깨진다.
		PracticeRiskSnapshot second = snapshot(
			attempt, 2, ExitPreset.BALANCED, buyTrade(103L, OrderType.LIMIT), "8439", "9135");
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(first, second));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L, 103L))).thenReturn(List.of(
			summary("10000", "1", "9750", "1", -2_505L, 10_005L, stopLossSell),
			summary("8700", "1", "9135", "1", 4_341L, 8_704L, takeProfitSell)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of(
			501L, ExitPlanStatus.FILLED_STOP_LOSS, 502L, ExitPlanStatus.FILLED_TAKE_PROFIT));

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, null);

		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).entrySequence()).isEqualTo(1);
		assertThat(entries.get(0).exitPreset()).isEqualTo("CAUTIOUS");
		assertThat(entries.get(0).sellCause()).isEqualTo("STOP_LOSS");
		assertThat(entries.get(0).sellAt()).isEqualTo(NOW.minusMinutes(10));
		assertThat(entries.get(0).realizedPnl()).isEqualTo(-2_505L);
		assertThat(entries.get(1).entrySequence()).isEqualTo(2);
		assertThat(entries.get(1).exitPreset()).isEqualTo("BALANCED");
		assertThat(entries.get(1).sellCause()).isEqualTo("TAKE_PROFIT");
		assertThat(entries.get(1).sellAt()).isEqualTo(NOW.minusMinutes(2));
		assertThat(entries.get(1).realizedPnl()).isEqualTo(4_341L);
		assertThat(entries.get(0).buyOrderType()).isEqualTo("MARKET");
		assertThat(entries.get(1).buyOrderType()).isEqualTo("LIMIT");
		// 대본 기준가가 없으면(생성기 버전 1) 이 값만 비어 나가고 나머지는 그대로 채워진다.
		assertThat(entries).extracting(PracticeEntryResponse::unrealizedPnlIfHeld).containsOnlyNulls();
	}

	/**
	 * {@code (기준가 × 팔린 수량 − FLOOR(금액 × 0.05%)) − soldBuyBasis}.
	 *
	 * <p>7900 × 2 = 15800, 수수료 FLOOR(15800 × 0.0005) = 7, basis 20010 → 15800 − 7 − 20010 = -4217.
	 * <b>매도 수수료를 빼지 않으면 -4210</b>이라 실현손익과 기준이 어긋난 채 가상 쪽이 유리해 보인다.
	 */
	@Test
	void unrealizedPnlIfHeldSubtractsTheSellFeeSoItSitsOnTheSameAxisAsRealizedPnl() {
		PracticeAttempt attempt = attempt();
		PracticeRiskSnapshot entry = snapshot(attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500");
		Trade sell = sellTrade(102L, 501L, NOW);
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "2", "9750", "2", -5_010L, 20_010L, sell)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, new BigDecimal("7900"));

		assertThat(entries.get(0).unrealizedPnlIfHeld()).isEqualTo(-4_217L);
		// 두 금액이 팔린 수량 기준임을 화면이 알 수 있어야 한다 — 부분 매도에서 buyQuantity와 갈린다.
		assertThat(entries.get(0).sellQuantity()).isEqualByComparingTo(new BigDecimal("2"));
		// 예약이 가리키지 않는 매도는 전부 수동이다.
		assertThat(entries.get(0).sellCause()).isEqualTo("MANUAL");
	}

	// 보유 중인 진입에는 대조가 없다 — "안 팔았다면"은 판 사람에게만 의미가 있다.
	@Test
	void heldEntryLeavesSellAndComparisonFieldsEmpty() {
		PracticeAttempt attempt = attempt();
		// 052 이전에 만들어진 행(세 컬럼이 모두 null)을 만든다 — 팩토리는 이제 비율을 항상 채우므로
		// 그 상태는 필드를 직접 비워야만 재현된다.
		PracticeRiskSnapshot entry = legacySnapshot(
			snapshot(attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500"));
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "2", null, "0", null, null, null)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, new BigDecimal("7900"));

		assertThat(entries.get(0).sellPrice()).isNull();
		assertThat(entries.get(0).sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(entries.get(0).sellAt()).isNull();
		assertThat(entries.get(0).sellCause()).isNull();
		assertThat(entries.get(0).unrealizedPnlIfHeld()).isNull();
		// 기능 도입 전에 만들어진 행(exit_preset이 null)은 기본 프리셋으로 해석한다.
		assertThat(entries.get(0).exitPreset()).isEqualTo(ExitPreset.DEFAULT.name());
	}

	@Test
	void runWithoutAnyEntryReturnsAnEmptyArrayWithoutReadingTheLedger() {
		PracticeAttempt attempt = attempt();
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of());

		assertThat(service.findCurrentRunEntries(attempt, new BigDecimal("7900"))).isEmpty();
	}

	private static PracticeRunTradeSummaryDto summary(
		String buyPrice, String buyQuantity, String sellPrice, String sellQuantity, Long realizedPnl,
		Long soldBuyBasis, Trade firstSell) {
		return new PracticeRunTradeSummaryDto(
			new BigDecimal(buyQuantity),
			new BigDecimal(sellQuantity),
			new BigDecimal(buyQuantity).subtract(new BigDecimal(sellQuantity)).max(BigDecimal.ZERO),
			firstSell,
			new BigDecimal(buyPrice),
			sellPrice == null ? null : new BigDecimal(sellPrice),
			realizedPnl,
			soldBuyBasis);
	}

	private static PracticeRiskSnapshot snapshot(
		PracticeAttempt attempt, int entrySequence, ExitPreset preset, Trade buyTrade, String stopLoss,
		String takeProfit) {
		return snapshot(attempt, entrySequence, preset, buyTrade, stopLoss, takeProfit, null);
	}

	/** 042·052 이전에 만들어진 행 — exit_preset과 비율 두 컬럼이 모두 비어 있다. */
	private static PracticeRiskSnapshot legacySnapshot(PracticeRiskSnapshot snapshot) {
		ReflectionTestUtils.setField(snapshot, "exitPreset", null);
		ReflectionTestUtils.setField(snapshot, "exitStopLossRate", null);
		ReflectionTestUtils.setField(snapshot, "exitTakeProfitRate", null);
		return snapshot;
	}

	private static PracticeRiskSnapshot snapshot(
		PracticeAttempt attempt, int entrySequence, ExitPreset preset, Trade buyTrade, String stopLoss,
		String takeProfit, TutorialScenarioScriptId scenarioScriptId) {
		return PracticeRiskSnapshot.create(
			attempt, 1L, entrySequence, ExitRates.of(preset), buyTrade, new BigDecimal("10000"),
			new BigDecimal(stopLoss), new BigDecimal(takeProfit), scenarioScriptId, NOW);
	}

	private static Trade buyTrade(long id) {
		return buyTrade(id, OrderType.MARKET);
	}

	// 이슈 #503 — 진입 응답이 그 진입을 연 매수 주문의 유형을 싣는다. 매수 체결에도 주문이 붙어 있어야 한다.
	private static Trade buyTrade(long id, OrderType orderType) {
		Order order = mock(Order.class);
		when(order.getOrderType()).thenReturn(orderType);
		Trade trade = mock(Trade.class);
		when(trade.getId()).thenReturn(id);
		when(trade.getOrder()).thenReturn(order);
		when(trade.getExecutedAt()).thenReturn(NOW.minusMinutes(30));
		return trade;
	}

	private static Trade sellTrade(long id, long orderId, LocalDateTime executedAt) {
		Order order = mock(Order.class);
		when(order.getId()).thenReturn(orderId);
		Trade trade = mock(Trade.class);
		when(trade.getId()).thenReturn(id);
		when(trade.getOrder()).thenReturn(order);
		when(trade.getExecutedAt()).thenReturn(executedAt);
		return trade;
	}

	private static PracticeAttempt attempt() {
		return attempt(TutorialPriceGenerator.VERSION_2, null);
	}

	private static PracticeAttempt attempt(short generatorVersion, TutorialScenarioScriptId scenarioScriptId) {
		PracticeAttempt attempt = PracticeAttempt.create(7L, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "SANDBOX_COIN_1", "알파코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, NOW, NOW.toLocalDate(), 1L, generatorVersion, scenarioScriptId, NOW);
		return attempt;
	}

	// 049 ORDERBASICS-023 — 완료 대조 배열의 진입마다 대본 식별자가 어떻게 해석되는지(plan.md §3-A 표).
	@Test
	void entryOpenedUnderOrderBasicsScriptCarriesThatScriptId() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_STORY_V1);
		PracticeRiskSnapshot entry = snapshot(
			attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500",
			TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "1", null, "0", null, null, null)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, null);

		assertThat(entries.get(0).scenarioScriptId()).isEqualTo("CRYPTO_ORDER_BASICS_V1");
	}

	@Test
	void entryOpenedUnderStoryScriptCarriesThatScriptId() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_STORY_V1);
		PracticeRiskSnapshot entry = snapshot(
			attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500",
			TutorialScenarioScriptId.CRYPTO_STORY_V1);
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "1", null, "0", null, null, null)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, null);

		assertThat(entries.get(0).scenarioScriptId()).isEqualTo("CRYPTO_STORY_V1");
	}

	// 049 이전에 만들어진 스냅샷(컬럼 도입 전)은 값이 NULL이지만, attempt가 대본을 쓰는 실행이면
	// CRYPTO_STORY_V1(041 대본)로 해석한다 — PracticeAttempt.scenarioScriptId()의 NULL 해석과 대칭이다.
	@Test
	void preExistingSnapshotWithNullScriptIdColumnResolvesToStoryScript() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_STORY_V1);
		PracticeRiskSnapshot entry = snapshot(attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500", null);
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "1", null, "0", null, null, null)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, null);

		assertThat(entries.get(0).scenarioScriptId()).isEqualTo("CRYPTO_STORY_V1");
	}

	// 대본을 쓰지 않는 실행(생성기 버전 1)은 attempt.usesScenarioScript()가 false라 스냅샷 컬럼값과
	// 무관하게 항상 null이다.
	@Test
	void entryFromNonScriptedRunResolvesToNull() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_1, null);
		PracticeRiskSnapshot entry = snapshot(attempt, 1, ExitPreset.BALANCED, buyTrade(101L), "9700", "10500", null);
		when(snapshotRepository.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(ATTEMPT_ID, 1L))
			.thenReturn(List.of(entry));
		when(tradeService.summarizePracticeRunEntries(ATTEMPT_ID, 1L, List.of(101L))).thenReturn(List.of(
			summary("10000", "1", null, "0", null, null, null)));
		when(exitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, 1L)).thenReturn(Map.of());

		List<PracticeEntryResponse> entries = service.findCurrentRunEntries(attempt, null);

		assertThat(entries.get(0).scenarioScriptId()).isNull();
	}
}
