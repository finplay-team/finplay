package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanIdempotencyKey;
import com.finplay.api.domain.order.repository.ExitPlanIdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExitPlanIdempotentCreationService {

	private final ExitPlanCreationService exitPlanCreationService;
	private final ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository;

	@Transactional
	public ExitPlan create(ExitPlanCreateCommandDto command, String idempotencyKey) {
		ExitPlan plan = exitPlanCreationService.create(command);
		exitPlanIdempotencyKeyRepository.save(
			ExitPlanIdempotencyKey.of(command.user(), idempotencyKey, command.requestHash(), plan,
				plan.getReservedAt()));
		return plan;
	}
}
