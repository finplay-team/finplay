// OCO 예약의 손절·익절 개별 조건 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.ExitPlanCondition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitPlanConditionRepository extends JpaRepository<ExitPlanCondition, Long> {

	List<ExitPlanCondition> findByExitPlanIdOrderByIdAsc(Long exitPlanId);
}
