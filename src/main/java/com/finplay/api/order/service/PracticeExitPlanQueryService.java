// 튜토리얼 실행 세대의 OCO 예약이 어떤 매도 주문을 발동시켰는지 되짚는 읽기 전용 서비스 (042 EXITPRESET-008)
package com.finplay.api.order.service;

import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.repository.ExitPlanRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매도가 손절·익절·수동 중 무엇이었는지는 {@code exit_plans.triggered_order_id} 역참조로만 알 수 있다.
 * {@code sellVerdict}로는 구분할 수 없다 — 자동 예약 체결이면 그 값이 정의상 항상 경계값이 되기 때문이다.
 *
 * <p>education이 {@code ExitPlanRepository}를 직접 주입하지 않도록 이 서비스만 거치게 한다(ADR-0002).
 */
@Service
@RequiredArgsConstructor
public class PracticeExitPlanQueryService {

	private final ExitPlanRepository exitPlanRepository;

	/** 현재 실행 세대에서 예약이 발동시킨 매도 주문 id → 그 예약의 최종 상태. 발동 이력이 없으면 빈 map이다. */
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
