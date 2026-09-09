package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.ExitPlanPracticeOriginDto;
import com.finplay.api.domain.order.service.ExitPriceInputDto;
import com.finplay.api.domain.order.service.PracticeOrderAttributionDto;
import com.finplay.api.domain.order.service.PracticeOrderAttributionPort;
import com.finplay.api.domain.order.service.PracticeOrderFillAttributionDto;
import com.finplay.api.domain.order.service.PracticeOrderFillContextDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptOrderAttributionService implements PracticeOrderAttributionPort {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final TradeService tradeService;
	private final HoldingService holdingService;
	private final ExitPlanCreationService exitPlanCreationService;
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService;
	private final Clock clock;

	@Transactional
	@Override
	public Optional<PracticeOrderAttributionDto> lockForOrder(
		Long userId, Instrument instrument, OrderType orderType) {
		if (!instrument.isTutorialSample()) {
			return Optional.empty();
		}
		Optional<PracticeAttempt> foundAttempt = practiceAttemptRepository
			.findByUserIdAndMarketForUpdate(userId, instrument.getMarket());
		if (foundAttempt.isEmpty()) {
			return Optional.empty();
		}
		PracticeAttempt attempt = foundAttempt.get();
		validateCurrentRun(attempt, instrument, attempt.getRunNumber());
		requireStageUnlocked(attempt, orderType);
		return Optional.of(new PracticeOrderAttributionDto(
			attempt.getId(), attempt.getRunNumber(),
			canonicalPriceService.canonicalPrice(attempt, LocalDateTime.now(clock))));
	}

	private void requireStageUnlocked(PracticeAttempt attempt, OrderType orderType) {
		if (!attempt.usesScenarioScript() || orderType == OrderType.MARKET) {
			return;
		}
		PracticeStageProgressResponse progress = practiceStageProgressCalculationService.calculate(attempt);
		if (!progress.marketBuySellCompleted()) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
	}

	@Transactional
	@Override
	public PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt) {
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(attribution.attemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (!attempt.getUserId().equals(attribution.userId())
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(attribution.instrumentId())) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		boolean currentRun = attempt.getStatus() == PracticeAttemptStatus.IN_PROGRESS
			&& attempt.getRunNumber() == attribution.runNumber();
		return new PracticeOrderFillContextDto(
			currentRun, currentRun ? canonicalPriceService.canonicalPrice(attempt, pricedAt) : null);
	}

	@Transactional
	@Override
	public void createRiskSnapshotOnBuyFill(Order order, Trade trade, LocalDateTime createdAt) {
		if (order.getPracticeAttemptId() == null || order.getSide() != OrderSide.BUY) {
			return;
		}
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(order.getPracticeAttemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		validateCurrentRun(attempt, order.getInstrument(), order.getPracticeAttemptRunNumber());
		if (heldBeforeThisFill(attempt, trade).signum() > 0) {
			return;
		}

		ExitRates rates = attempt.effectiveExitRates();
		ReferencePriceLines lines = referencePriceCalculator.calculateFromRates(trade.getPrice(), rates);
		int entrySequence = Math.toIntExact(practiceRiskSnapshotRepository
			.countByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())) + 1;
		practiceRiskSnapshotRepository.save(PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			entrySequence,
			rates,
			trade,
			referencePriceCalculator.normalizeEntryPrice(trade.getPrice()),
			lines.referenceStopLossPrice(),
			lines.referenceTakeProfitPrice(),
			attempt.scenarioScriptId(),
			createdAt));

		if (attempt.getMarket() == Market.CRYPTO && automaticExitPlanAllowed(attempt)) {
			createAutomaticExitPlan(attempt, trade, rates, entrySequence, createdAt);
		}
	}

	private boolean automaticExitPlanAllowed(PracticeAttempt attempt) {
		return attempt.scenarioScriptId() == null;
	}

	@Transactional(readOnly = true)
	@Override
	public boolean managesAutomaticExitPlans(Long practiceAttemptId, Long practiceAttemptRunNumber) {
		if (practiceAttemptId == null || practiceAttemptRunNumber == null) {
			return true;
		}
		return practiceAttemptRepository.findById(practiceAttemptId)
			.map(attempt -> attempt.getRunNumber() != practiceAttemptRunNumber
				|| automaticExitPlanAllowed(attempt))
			.orElse(true);
	}

	private void createAutomaticExitPlan(
		PracticeAttempt attempt, Trade trade, ExitRates rates, int entrySequence, LocalDateTime createdAt) {
		Long holdingId = holdingService
			.findHoldingId(attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		Holding holding = holdingService.findHoldingForOwner(attempt.getUserId(), holdingId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPercent(
			referencePriceCalculator.normalizeEntryPrice(trade.getPrice()),
			rates.stopLossRate(),
			rates.takeProfitRate());
		exitPlanCreationService.create(ExitPlanCreateCommandDto.practice(
			trade.getAccount().getUser(),
			holding,
			trade.getQuantity(),
			priceInput,
			ExitPlanPracticeOriginDto.auditRequestHash(attempt.getId(), attempt.getRunNumber(), entrySequence),
			new ExitPlanPracticeOriginDto(
				attempt.getId(),
				attempt.getRunNumber(),
				canonicalPriceService.canonicalPrice(attempt, createdAt))));
	}

	private BigDecimal heldBeforeThisFill(PracticeAttempt attempt, Trade trade) {
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber())
			.subtract(trade.getQuantity());
	}

	private void validateCurrentRun(PracticeAttempt attempt, Instrument instrument, long runNumber) {
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.IN_PROGRESS
			|| attempt.getRunNumber() != runNumber
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(instrument.getId())) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
	}
}
