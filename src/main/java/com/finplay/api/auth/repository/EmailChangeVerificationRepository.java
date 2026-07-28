// 이메일 변경 인증번호의 회원별 발송 횟수 집계·무효화 대상 조회를 담당하는 JPA 리포지터리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.EmailChangeVerification;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailChangeVerificationRepository extends JpaRepository<EmailChangeVerification, Long> {

	// 발송 제한 판정용 기간별 발송 횟수 집계 — 대상 이메일과 무관하게 회원 단위로 합산한다 (INDEX(user_id, created_at) 활용).
	long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

	// 재발송 시 무효화 대상인 유효한 미소비 행 조회 — 무효화 범위는 (회원, 새 이메일) 쌍으로 좁힌다.
	List<EmailChangeVerification> findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(
		Long userId, String newEmail, LocalDateTime now);
}
