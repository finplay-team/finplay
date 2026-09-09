package com.finplay.api.domain.order.service;

import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExitPlanCreationService {

	private final PortfolioSellService portfolioSellService;
	private final PriceQueryService priceQueryService;
	private final ExitPricePolicy exitPricePolicy;
	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final Clock clock;

	@Transactional
	public ExitPlan create(ExitPlanCreateCommandDto command) {
		Holding holding = lockHolding(command.holding());

		validateNoPendingPlan(holding);

		validateAvailableQuantity(holding, command);

		ExitPriceLinesDto lines = exitPricePolicy.resolve(command.priceInput());

		ExitPlanBaselineDto baseline = resolveBaseline(command, holding);

		return reserveAndSave(command, holding, lines, baseline);
	}

	private Holding lockHolding(Holding holding) {
		return portfolioSellService.getHoldingForUpdateForExitPlanCreation(holding.getAccount(),
			holding.getInstrument());
	}

	private ExitPlanBaselineDto resolveBaseline(ExitPlanCreateCommandDto command, Holding holding) {
		if (command.isPracticePath()) {
			return new ExitPlanBaselineDto(command.practiceOrigin().baselinePrice(), LocalDateTime.now(clock));
		}
		PriceQuoteDto quote = priceQueryService.getPrice(holding.getInstrument().getId());
		return new ExitPlanBaselineDto(quote.price(), quote.sourceTime());
	}

	private void validateNoPendingPlan(Holding holding) {
		if (exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_ALREADY_EXISTS);
		}
	}

	private void validateAvailableQuantity(Holding holding, ExitPlanCreateCommandDto command) {
		if (holding.getAvailableQuantity().compareTo(command.quantity()) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
		}
	}

	private ExitPlan reserveAndSave(
		ExitPlanCreateCommandDto command, Holding holding, ExitPriceLinesDto lines, ExitPlanBaselineDto baseline) {
		LocalDateTime now = LocalDateTime.now(clock);
		holding.reserveQuantity(command.quantity());

		ExitPlan plan = exitPlanRepository.save(newExitPlan(command, holding, lines, baseline, now));
		exitPlanConditionRepository.save(
			ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, lines.stopLossPrice(), now));
		exitPlanConditionRepository.save(
			ExitPlanCondition.create(plan, ExitPlanConditionType.TAKE_PROFIT, lines.takeProfitPrice(), now));
		return plan;
	}

	private ExitPlan newExitPlan(
		ExitPlanCreateCommandDto command, Holding holding, ExitPriceLinesDto lines, ExitPlanBaselineDto baseline,
		LocalDateTime now) {
		ExitPriceInputDto priceInput = command.priceInput();
		LocalDateTime baselineObservedAt = baseline.observedAt() != null ? baseline.observedAt() : now;
		if (command.isPracticePath()) {
			ExitPlanPracticeOriginDto practiceOrigin = command.practiceOrigin();
			return ExitPlan.createPractice(
				command.user(),
				holding,
				holding.getInstrument(),
				command.quantity(),
				priceInput.entryPrice(),
				priceInput.exitPriceType(),
				priceInput.stopLossRate(),
				priceInput.takeProfitRate(),
				lines.stopLossPrice(),
				lines.takeProfitPrice(),
				baseline.price(),
				baselineObservedAt,
				command.requestHash(),
				practiceOrigin.attemptId(),
				practiceOrigin.runNumber(),
				now);
		}
		if (!command.isEducationalPath()) {
			return ExitPlan.createGeneral(
				command.user(),
				holding,
				holding.getInstrument(),
				command.quantity(),
				priceInput.entryPrice(),
				priceInput.exitPriceType(),
				priceInput.stopLossRate(),
				priceInput.takeProfitRate(),
				lines.stopLossPrice(),
				lines.takeProfitPrice(),
				baseline.price(),
				baselineObservedAt,
				command.requestHash(),
				now);
		}
		ExitPlanEducationalOriginDto origin = command.educationalOrigin();
		return ExitPlan.createEducational(
			command.user(),
			holding,
			origin.intentionId(),
			origin.intentionInstanceKey(),
			origin.buyTrade(),
			holding.getInstrument(),
			command.quantity(),
			priceInput.entryPrice(),
			priceInput.exitPriceType(),
			priceInput.stopLossRate(),
			priceInput.takeProfitRate(),
			lines.stopLossPrice(),
			lines.takeProfitPrice(),
			baseline.price(),
			baselineObservedAt,
			command.requestHash(),
			now);
	}
}
