// OCO 손절·익절 예약의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitPlanRepository extends JpaRepository<ExitPlan, Long> {

	// 본인 소유 예약 단건 조회 — 타인 소유는 존재를 숨겨 404로 응답하기 위해 userId를 조건에 포함한다(021 spec).
	Optional<ExitPlan> findByIdAndUserId(Long id, Long userId);

	// holding당 PENDING 1건 불변식 검증용(021 plan "holding당 PENDING 1건"). 호출부가 holding을 먼저
	// 비관 잠금하므로 이 조회 자체는 락을 걸지 않는다.
	boolean existsByHoldingIdAndStatus(Long holdingId, ExitPlanStatus status);

	List<ExitPlan> findByUserIdAndStatusOrderByIdDesc(Long userId, ExitPlanStatus status);
}
