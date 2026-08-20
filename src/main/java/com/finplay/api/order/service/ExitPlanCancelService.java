// OCO 손절·익절 예약 1건을 취소(예약 해제)하는 서비스 — 잠금 순서 holding → plan(021 plan.md, 경로 공통)
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanConditionStatus;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 021 plan.md "트리거·취소·잠금 순서" 표의 "사용자 취소" 행({@code holding → plan})을 구현한다. 존재하지 않거나
 * 타인 소유는 존재를 숨겨 {@code EXIT_PLAN_NOT_FOUND} 404로, {@code PENDING}이 아니면 {@code
 * EXIT_PLAN_NOT_PENDING} 409로 거부한다(검증 순서는 항상 존재 → 상태). 예약 해제는 {@code
 * ai/specs/015-limit-order}가 만든 {@code Holding.releaseReservedQuantity}를 그대로 재사용한다.
 */
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
		// 존재·소유권 확인 겸 holding 참조 확보 — 이 조회 자체는 잠그지 않는다(holding을 먼저 잠가야 하므로).
		ExitPlan ownershipCheck = exitPlanRepository.findByIdAndUserId(exitPlanId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));

		// ownershipCheck.getHolding()(non-id 접근)이 holding을 이미 1급 캐시에 올려둔다 — 이후 잠금 쿼리(FOR
		// UPDATE)가 실행돼도 Hibernate는 이미 세션에 있는 같은 id 인스턴스를 필드 갱신 없이 그대로 반환하므로,
		// 잠금 직전까지 다른 트랜잭션이 커밋한 변경(reservedQuantity·status)을 보지 못한다(2026-08-17
		// ExitPlanCancelFillConcurrencyIntegrationTest에서 재현 — "정확히 한 번" 규칙 위반). entityManager.clear()로
		// 세션 전체를 비우면 이 메서드가 같은 트랜잭션을 공유하는 다른 호출자(OSIV 없는 이 앱에서도 테스트의
		// @Transactional처럼 더 넓은 트랜잭션 안에서 호출될 수 있다)가 이미 들고 있는 무관한 엔티티까지 분리돼
		// 500으로 이어질 수 있어(재현 확인), 이 메서드가 직접 로딩한 두 엔티티만 선택적으로 detach한다.
		Holding preloadedHolding = ownershipCheck.getHolding();
		Account accountRef = preloadedHolding.getAccount();
		Instrument instrumentRef = ownershipCheck.getInstrument();
		// detach 전에 반드시 flush한다 — 같은 트랜잭션 안에서 이미 이 두 엔티티에 가해진(예: 테스트의
		// @Transactional처럼 더 넓은 트랜잭션을 공유할 때 앞서 호출된 생성 로직의 reserveQuantity) 아직 flush되지
		// 않은 변경을 detach가 그대로 버리면, 아래 재조회가 DB의 예전 값을 읽어 "예약된 수량보다 큰 수량을 해제"
		// 같은 원장 불일치를 낸다(재현 확인).
		entityManager.flush();
		entityManager.detach(ownershipCheck);
		entityManager.detach(preloadedHolding);

		// 잠금 순서 holding → plan — 가격 트리거·취소가 같은 순서를 쓰므로 데드락이 없다(021 plan.md).
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
