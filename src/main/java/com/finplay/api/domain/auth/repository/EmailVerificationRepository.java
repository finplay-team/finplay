// 이메일 인증번호의 발송 횟수 집계·미확인 행 조회를 담당하는 JPA 리포지토리
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

	// 발송 제한 판정용 기간별 발송 횟수 집계 (INDEX(email, created_at) 범위 활용, 60초·1시간·하루 창은 service가 지정).
	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	// 재발송 시 무효화 대상인 유효한 미확인 행 조회 (verified_at 널 = 미확인, expires_at 이후 = 아직 유효).
	List<EmailVerification> findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(String email, LocalDateTime now);

	// 확인 대상 최신 행 조회 — 이메일별 가장 최근 1건을 찾는다.
	// 행 잠금(SELECT ... FOR UPDATE)이 필수다 — 잠금 없이 읽으면 동시 요청이 같은 attempt_count를 읽고 같은 값 + 1을 써서
	// 병렬 요청 N개가 시도 1회로 계산되고, 이 비인증 경로의 유일한 무차별 대입 방어선인 5회 제한이 무력화된다.
	// 잠금은 호출자(EmailVerificationService.confirmVerificationCode)의 트랜잭션이 커밋될 때까지 유지되어
	// 읽기-판정-증가가 직렬화된다.
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
