package com.finplay.api.domain.education.priceruntime.repository;

import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PracticePriceSessionRepository extends JpaRepository<PracticePriceSession, Long> {

	Optional<PracticePriceSession> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndInstrumentIdAndStatus(Long userId, Long instrumentId, PracticePriceSessionStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM PracticePriceSession s WHERE s.id = :id AND s.userId = :userId")
	Optional<PracticePriceSession> findByIdAndUserIdForUpdate(@Param("id")
	Long id, @Param("userId")
	Long userId);
}
