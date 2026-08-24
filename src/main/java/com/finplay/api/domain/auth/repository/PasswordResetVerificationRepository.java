// 비밀번호 재설정 인증번호의 이메일별 요청 횟수 집계·무효화 대상 조회를 담당하는 JPA 리포지터리
package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PasswordResetVerificationRepository extends JpaRepository<PasswordResetVerification, Long> {

	// 발송 제한 집계 — 미가입·소셜 전용으로 거부된 행까지 포함해 이메일 단위로 센다 (INDEX(email, created_at) 활용).
	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	// 재발송 시 무효화 대상 — 실제로 발송된(code_hash가 있는) 유효·미소비 행만 고른다.
	List<PasswordResetVerification> findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(
		String email, LocalDateTime now);

	// 확인 대상 최신 행 조회 — 거부 행(code_hash NULL)은 건너뛰고 실제로 발송된 가장 최근 1건을 찾는다.
	// 행 잠금(SELECT ... FOR UPDATE)이 필수다 — 잠금 없이 읽으면 동시 요청이 같은 attempt_count를 읽고 같은 값 + 1을 써서
	// 병렬 요청 N개가 시도 1회로 계산되고, 이 비인증 경로의 유일한 무차별 대입 방어선인 5회 제한이 무력화된다.
	// 잠금은 호출자(AuthService.confirmPasswordReset)의 트랜잭션이 커밋될 때까지 유지되어 읽기-판정-증가가 직렬화된다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PasswordResetVerification> findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(String email);
}
