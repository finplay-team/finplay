// 비밀번호 재설정 인증번호의 발송 상태와 거부된 요청 이력을 저장하는 엔티티 (원문 미저장, 해시만 보관)
package com.finplay.api.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "password_reset_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetVerification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	// 발송하지 않은 거부 행(미가입·소셜 전용)은 NULL이다.
	@Column(name = "code_hash")
	private String codeHash;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "expires_at")
	private LocalDateTime expiresAt;

	@Column(name = "last_sent_at")
	private LocalDateTime lastSentAt;

	@Column(name = "consumed_at")
	private LocalDateTime consumedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PasswordResetVerification(
		String email, String codeHash, LocalDateTime expiresAt, LocalDateTime lastSentAt, LocalDateTime now) {
		this.email = email;
		this.codeHash = codeHash;
		this.attemptCount = 0;
		this.expiresAt = expiresAt;
		this.lastSentAt = lastSentAt;
		this.createdAt = now;
	}

	public static PasswordResetVerification create(
		String email, String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
		return new PasswordResetVerification(email, codeHash, expiresAt, now, now);
	}

	// 미가입·소셜 전용 계정으로 거부된 요청도 발송 제한 집계 대상이라 행만 남긴다 — 발송하지 않았으므로 코드·만료·발송시각은 없다.
	public static PasswordResetVerification createRejected(String email, LocalDateTime now) {
		return new PasswordResetVerification(email, null, null, null, now);
	}

	// 재발송 시 같은 이메일의 이전 인증번호를 즉시 무효화한다 — 유효한 인증번호는 항상 최대 1개.
	public void expire(LocalDateTime now) {
		this.expiresAt = now;
	}

	public int incrementAttemptCount() {
		return ++this.attemptCount;
	}

	public void consume(LocalDateTime now) {
		this.consumedAt = now;
	}
}
