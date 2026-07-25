// 이메일 인증번호의 발송 횟수 집계·미확인 행 조회를 담당하는 JPA 리포지토리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.EmailVerification;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

	// 발송 제한 판정용 기간별 발송 횟수 집계 (INDEX(email, created_at) 범위 활용, 60초·1시간·하루 창은 service가 지정).
	long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

	// 재발송 시 무효화 대상인 유효한 미확인 행 조회 (verified_at 널 = 미확인, expires_at 이후 = 아직 유효).
	List<EmailVerification> findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(String email, LocalDateTime now);
}
