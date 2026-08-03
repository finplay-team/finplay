// 이메일 변경 인증번호의 회원별 발송 횟수 집계·무효화 대상 조회를 담당하는 JPA 리포지터리
package com.finplay.api.auth.repository;

import com.finplay.api.auth.domain.EmailChangeVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailChangeVerificationRepository extends JpaRepository<EmailChangeVerification, Long> {

	// 발송 제한 판정용 기간별 발송 횟수 집계 — 대상 이메일과 무관하게 회원 단위로 합산한다 (INDEX(user_id, created_at) 활용).
	long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

	// 재발송 시 무효화 대상인 유효한 미소비 행 조회 — 무효화 범위는 (회원, 새 이메일) 쌍으로 좁힌다.
	List<EmailChangeVerification> findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(
		Long userId, String newEmail, LocalDateTime now);

	// 확인 대상 최신 행 조회 — 같은 회원·같은 새 이메일 조합에서 가장 최근에 발송된 1건을 찾는다.
	// 행 잠금(SELECT ... FOR UPDATE)이 필수다 — 잠금 없이 읽으면 동시 요청이 같은 attempt_count를 읽고 같은 값 + 1을 써서
	// 병렬 요청 N개가 시도 1회로 계산되고, 무차별 대입 방어선인 5회 제한이 무력화된다.
	// 잠금은 호출자(AuthService.confirmEmailChange)의 트랜잭션이 커밋될 때까지 유지되어 읽기-판정-증가가 직렬화된다.
	// 잠기는 행은 email_change_verifications 1건뿐이다 — user는 LAZY @ManyToOne이라 조회에 users 조인이 붙지 않는다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<EmailChangeVerification> findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(Long userId, String newEmail);
}
