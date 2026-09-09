package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.EmailVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	List<EmailVerification> findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(String email, LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDesc(String email);

	Optional<EmailVerification> findByTokenHash(String tokenHash);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update EmailVerification verification
		   set verification.consumedAt = :now
		 where verification.tokenHash = :tokenHash
		   and verification.verifiedAt is not null
		   and verification.consumedAt is null
		   and verification.tokenExpiresAt > :now
		""")
	int consumeValidToken(@Param("tokenHash")
	String tokenHash, @Param("now")
	LocalDateTime now);
}
