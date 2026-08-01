// 비밀번호 재설정 인증번호의 이메일별 요청 횟수 집계·무효화 대상 조회를 담당하는 JPA 리포지터리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.PasswordResetVerification;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetVerificationRepository extends JpaRepository<PasswordResetVerification, Long> {

	// 발송 제한 집계 — 미가입·소셜 전용으로 거부된 행까지 포함해 이메일 단위로 센다 (INDEX(email, created_at) 활용).
	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	// 재발송 시 무효화 대상 — 실제로 발송된(code_hash가 있는) 유효·미소비 행만 고른다.
	List<PasswordResetVerification> findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(
		String email, LocalDateTime now);
}
