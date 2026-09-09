package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExitPlanCancelService {

	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final PortfolioSellService portfolioSellService;
	private final Clock clock;
	private final EntityManager entityManager;

	@Transactional
	public void cancel(Long userId, Long exitPlanId) {
		ExitPlan ownershipCheck = exitPlanRepository.findByIdAndUserId(exitPlanId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));

		Holding preloadedHolding = ownershipCheck.getHolding();
		Account accountRef = preloadedHolding.getAccount();
		Instrument instrumentRef = ownershipCheck.getInstrument();
		entityManager.flush();
		entityManager.detach(ownershipCheck);
		entityManager.detach(preloadedHolding);

		Holding holding = portfolioSellService.getHoldingForUpdate(accountRef, instrumentRef);

		ExitPlan plan = exitPlanRepository.findByIdForUpdate(exitPlanId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));
		if (!plan.isPending()) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_NOT_PENDING);
		}

		holding.releaseReservedQuantity(plan.getQuantity());
		plan.cancel(LocalDateTime.now(clock));
		cancelPendingConditions(plan.getId());
	}

	private void cancelPendingConditions(Long planId) {
		exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(planId).forEach(condition -> {
			if (condition.getStatus() == ExitPlanConditionStatus.PENDING) {
				condition.cancel();
			}
		});
	}
}
