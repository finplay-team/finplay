package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.EmailChangeVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailChangeVerificationRepository extends JpaRepository<EmailChangeVerification, Long> {

	long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

	List<EmailChangeVerification> findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(
		Long userId, String newEmail, LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EmailChangeVerification> findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(Long userId, String newEmail);
}
