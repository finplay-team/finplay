package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeEntryComparisonService {

	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final TradeService tradeService;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	@Transactional(readOnly = true)
	public List<PracticeEntryResponse> findCurrentRunEntries(PracticeAttempt attempt, BigDecimal comparisonPrice) {
		List<PracticeRiskSnapshot> snapshots = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(attempt.getId(), attempt.getRunNumber());
		if (snapshots.isEmpty()) {
			return List.of();
		}
		List<PracticeRunTradeSummaryDto> summaries = tradeService.summarizePracticeRunEntries(
			attempt.getId(),
			attempt.getRunNumber(),
			snapshots.stream().map(snapshot -> snapshot.getBuyTrade().getId()).toList());
		Map<Long, ExitPlanStatus> triggered = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attempt.getId(), attempt.getRunNumber());

		List<PracticeEntryResponse> entries = new ArrayList<>(snapshots.size());
		for (int index = 0; index < snapshots.size(); index++) {
			entries.add(toEntry(snapshots.get(index), summaries.get(index), triggered, attempt, comparisonPrice));
		}
		return List.copyOf(entries);
	}

	private PracticeEntryResponse toEntry(
		PracticeRiskSnapshot snapshot,
		PracticeRunTradeSummaryDto summary,
		Map<Long, ExitPlanStatus> triggeredSellOrderStatuses,
		PracticeAttempt attempt,
		BigDecimal comparisonPrice) {
		Trade sellTrade = summary.firstSellTrade();
		Market market = attempt.getMarket();
		ExitRates rates = snapshot.appliedExitRates();
		ExitPreset matchingPreset = rates.matchingPreset();
		return new PracticeEntryResponse(
			snapshot.getEntrySequence(),
			matchingPreset == null ? null : matchingPreset.name(),
			rates.stopLossRate(),
			rates.takeProfitRate(),
			snapshot.getBuyTrade().getOrder().getOrderType().name(),
			snapshot.getBuyTrade().getExecutedAt(),
			summary.averageBuyPrice(),
			summary.buyQuantity(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			summary.averageSellPrice(),
			summary.sellQuantity(),
			sellTrade == null ? null : sellTrade.getExecutedAt(),
			sellTrade == null
				? null
				: PracticeSellCause.from(triggeredSellOrderStatuses.get(sellTrade.getOrder().getId())).name(),
			summary.realizedPnl(),
			unrealizedPnlIfHeld(summary, market, comparisonPrice),
			scenarioScriptIdOf(attempt, snapshot));
	}

	private String scenarioScriptIdOf(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
		if (!attempt.usesScenarioScript()) {
			return null;
		}
		TutorialScenarioScriptId scenarioScriptId = snapshot.getScenarioScriptId();
		return (scenarioScriptId == null ? TutorialScenarioScriptId.CRYPTO_STORY_V1 : scenarioScriptId).name();
	}

	private Long unrealizedPnlIfHeld(
		PracticeRunTradeSummaryDto summary, Market market, BigDecimal comparisonPrice) {
		if (comparisonPrice == null || summary.soldBuyBasis() == null || summary.sellQuantity().signum() <= 0) {
			return null;
		}
		long amount = comparisonPrice.multiply(summary.sellQuantity())
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		long fee = BigDecimal.valueOf(amount)
			.multiply(market == Market.CRYPTO ? CRYPTO_FEE_RATE : STOCK_FEE_RATE)
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		return (amount - fee) - summary.soldBuyBasis();
	}
}
