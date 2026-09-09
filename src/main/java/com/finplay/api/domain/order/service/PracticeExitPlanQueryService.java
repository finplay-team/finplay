package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeExitPlanQueryService {

	private final ExitPlanRepository exitPlanRepository;

	@Transactional(readOnly = true)
	public PracticeRunExitPlanSummaryDto summarizeCurrentRun(Long attemptId, long runNumber) {
		boolean stopLossFilled = false;
		boolean takeProfitFilled = false;
		ExitPlan pendingPlan = null;
		for (ExitPlan plan : exitPlanRepository.findByPracticeAttemptIdAndPracticeAttemptRunNumber(
			attemptId, runNumber)) {
			if (plan.getStatus() == ExitPlanStatus.FILLED_STOP_LOSS) {
				stopLossFilled = true;
			}
			if (plan.getStatus() == ExitPlanStatus.FILLED_TAKE_PROFIT) {
				takeProfitFilled = true;
			}
			if (plan.isPending()) {
				pendingPlan = plan;
			}
		}
		return new PracticeRunExitPlanSummaryDto(
			stopLossFilled, takeProfitFilled, pendingPlan == null ? null : ExitPlanResponse.from(pendingPlan));
	}

	@Transactional(readOnly = true)
	public boolean existsEntryReservation(Long attemptId, long runNumber, String requestHash) {
		return exitPlanRepository
			.existsByPracticeAttemptIdAndPracticeAttemptRunNumberAndRequestHash(attemptId, runNumber, requestHash);
	}

	@Transactional(readOnly = true)
	public boolean existsRunReservation(Long attemptId, long runNumber) {
		return exitPlanRepository.existsByPracticeAttemptIdAndPracticeAttemptRunNumber(attemptId, runNumber);
	}

	@Transactional(readOnly = true)
	public Map<Long, ExitPlanStatus> findTriggeredSellOrderStatuses(Long attemptId, long runNumber) {
		Map<Long, ExitPlanStatus> statuses = new HashMap<>();
		for (ExitPlan plan : exitPlanRepository.findByPracticeAttemptIdAndPracticeAttemptRunNumber(
			attemptId, runNumber)) {
			if (plan.getTriggeredOrder() != null) {
				statuses.put(plan.getTriggeredOrder().getId(), plan.getStatus());
			}
		}
		return statuses;
	}
}
