// 튜토리얼 실행 세대의 OCO 예약이 어떤 매도 주문을 발동시켰는지 되짚는 읽기 전용 서비스 (042 EXITPRESET-008)
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

	/**
	 * 052 EXITFREE-020·021 — 현재 실행 세대의 예약 원장을 한 번 훑어 요약한다.
	 *
	 * <p>사용자 주도 예약 생성 경로(write-once 판정)와 진행 조회(예약 존재·겪음 상태)가 <b>같은 산출식</b>을
	 * 써야 한다 — 두 벌로 두면 화면이 "예약 가능"으로 그린 버튼이 서버에서 409로 거부된다(042가
	 * {@code exitPresetLocked}에서 이미 겪은 갈림이다).
	 */
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

	/**
	 * 052 EXITFREE-020 write-once — <b>그 진입에</b> 예약을 이미 한 번 만들었는가. 취소·체결된 예약도
	 * 포함한다.
	 *
	 * @param requestHash {@code ExitPlanPracticeOriginDto.auditRequestHash}가 만든 진입 식별 해시.
	 *                    자동 예약과 사용자 주도 예약이 같은 식을 쓰므로 어느 쪽이 만든 예약이든 잡힌다
	 */
	@Transactional(readOnly = true)
	public boolean existsEntryReservation(Long attemptId, long runNumber, String requestHash) {
		return exitPlanRepository
			.existsByPracticeAttemptIdAndPracticeAttemptRunNumberAndRequestHash(attemptId, runNumber, requestHash);
	}

	/**
	 * 052 EXITFREE-011 — 이 실행 세대에 예약이 하나라도 있는가. 상태를 묻지 않으므로 지금 대기 중이든
	 * 이미 체결·취소됐든 참이다.
	 *
	 * <p>{@link #existsEntryReservation}과 목적이 다르다 — 그쪽은 <b>그 진입에</b> 이미 만들었는지를 묻는
	 * write-once 판정이고, 이쪽은 <b>이 실행에서</b> 기준을 정한 적이 있는지를 묻는 단계 진행 판정이다.
	 */
	@Transactional(readOnly = true)
	public boolean existsRunReservation(Long attemptId, long runNumber) {
		return exitPlanRepository.existsByPracticeAttemptIdAndPracticeAttemptRunNumber(attemptId, runNumber);
	}

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
