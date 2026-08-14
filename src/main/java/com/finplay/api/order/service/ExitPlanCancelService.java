// OCO 손절·익절 예약 1건을 취소(예약 해제)하는 서비스 — 잠금 순서 holding → plan(021 plan.md, 경로 공통)
package com.finplay.api.order.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanConditionStatus;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 021 plan.md "트리거·취소·잠금 순서" 표의 "사용자 취소" 행({@code holding → plan})을 구현한다. 존재하지 않거나
 * 타인 소유는 존재를 숨겨 {@code EXIT_PLAN_NOT_FOUND} 404로, {@code PENDING}이 아니면 {@code
 * EXIT_PLAN_NOT_PENDING} 409로 거부한다(검증 순서는 항상 존재 → 상태). 예약 해제는 {@code
 * docs/specs/015-limit-order}가 만든 {@code Holding.releaseReservedQuantity}를 그대로 재사용한다.
 */
@Service
@RequiredArgsConstructor
public class ExitPlanCancelService {

	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final PortfolioSellService portfolioSellService;
	private final Clock clock;

	@Transactional
	public void cancel(Long userId, Long exitPlanId) {
		// 존재·소유권 확인 겸 holding 참조 확보 — 이 조회 자체는 잠그지 않는다(holding을 먼저 잠가야 하므로).
		ExitPlan ownershipCheck = exitPlanRepository.findByIdAndUserId(exitPlanId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));

		// 잠금 순서 holding → plan — 가격 트리거·취소가 같은 순서를 쓰므로 데드락이 없다(021 plan.md).
		Holding holding = portfolioSellService.getHoldingForUpdate(
			ownershipCheck.getHolding().getAccount(), ownershipCheck.getInstrument());

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
