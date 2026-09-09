package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordResetVerificationRepository extends JpaRepository<PasswordResetVerification, Long> {

	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	List<PasswordResetVerification> findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(
		String email, LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PasswordResetVerification> findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(String email);
}
