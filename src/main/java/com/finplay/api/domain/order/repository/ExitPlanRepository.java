package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExitPlanRepository extends JpaRepository<ExitPlan, Long> {

	Optional<ExitPlan> findByIdAndUserId(Long id, Long userId);

	boolean existsByHoldingIdAndStatus(Long holdingId, ExitPlanStatus status);

	List<ExitPlan> findByUserIdAndStatusOrderByIdDesc(Long userId, ExitPlanStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM ExitPlan p WHERE p.id = :id")
	Optional<ExitPlan> findByIdForUpdate(@Param("id")
	Long id);

	List<ExitPlan> findByPracticeAttemptIdAndPracticeAttemptRunNumber(Long practiceAttemptId,
		Long practiceAttemptRunNumber);

	boolean existsByPracticeAttemptIdAndPracticeAttemptRunNumberAndRequestHash(
		Long practiceAttemptId, Long practiceAttemptRunNumber, String requestHash);

	boolean existsByPracticeAttemptIdAndPracticeAttemptRunNumber(
		Long practiceAttemptId, Long practiceAttemptRunNumber);

	@Query("""
		select p.id from ExitPlan p
		where p.practiceAttemptId = :attemptId
		  and p.practiceAttemptRunNumber = :runNumber
		  and p.status = com.finplay.api.domain.order.entity.ExitPlanStatus.PENDING
		order by p.id asc
		""")
	List<Long> findPendingPracticeRunExitPlanIds(@Param("attemptId")
	Long attemptId, @Param("runNumber")
	long runNumber);

	@Query("""
		select p from ExitPlan p
		where p.instrument.id = :instrumentId and p.status = com.finplay.api.domain.order.entity.ExitPlanStatus.PENDING
		  and p.practiceAttemptId is null
		  and (p.takeProfitPrice <= :price or p.stopLossPrice >= :price)
		order by p.reservedAt asc, p.id asc
		""")
	List<ExitPlan> findPendingExitPlansToFill(@Param("instrumentId")
	Long instrumentId, @Param("price")
	BigDecimal price);
}
