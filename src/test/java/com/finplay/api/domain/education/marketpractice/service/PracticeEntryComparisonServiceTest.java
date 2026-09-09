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

	@Test
	void reentryRunSeparatesStopLossAndTakeProfitIntoTwoEntries() {
		PracticeAttempt attempt = attempt();
		Trade stopLossSell = sellTrade(102L, 501L, NOW.minusMinutes(10));
		Trade takeProfitSell = sellTrade(104L, 502L, NOW.minusMinutes(2));
		PracticeRiskSnapshot first = snapshot(attempt, 1, ExitPreset.CAUTIOUS, buyTrade(101L), "9700", "10500");
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
		assertThat(entries).extracting(PracticeEntryResponse::unrealizedPnlIfHeld).containsOnlyNulls();
	}

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
		assertThat(entries.get(0).sellQuantity()).isEqualByComparingTo(new BigDecimal("2"));
		assertThat(entries.get(0).sellCause()).isEqualTo("MANUAL");
	}

	@Test
	void heldEntryLeavesSellAndComparisonFieldsEmpty() {
		PracticeAttempt attempt = attempt();
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
