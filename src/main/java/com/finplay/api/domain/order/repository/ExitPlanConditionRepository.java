package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.ExitPlanCondition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExitPlanConditionRepository extends JpaRepository<ExitPlanCondition, Long> {

	List<ExitPlanCondition> findByExitPlanIdOrderByIdAsc(Long exitPlanId);
}
