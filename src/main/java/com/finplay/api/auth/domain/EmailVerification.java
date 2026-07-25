// 이메일 인증번호 발송·확인 상태를 저장하는 엔티티 (원문 미저장, 해시만 보관)
package com.finplay.api.auth.domain;

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
@Table(name = "email_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(name = "code_hash", nullable = false)
	private String codeHash;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "last_sent_at", nullable = false)
	private LocalDateTime lastSentAt;

	@Column(name = "verified_at")
	private LocalDateTime verifiedAt;

	@Column(name = "token_hash", unique = true)
	private String tokenHash;

	@Column(name = "token_expires_at")
	private LocalDateTime tokenExpiresAt;

	@Column(name = "consumed_at")
	private LocalDateTime consumedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private EmailVerification(String email, String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
		this.email = email;
		this.codeHash = codeHash;
		this.attemptCount = 0;
		this.expiresAt = expiresAt;
		this.lastSentAt = now;
		this.createdAt = now;
	}

	public static EmailVerification create(
		String email, String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
		return new EmailVerification(email, codeHash, expiresAt, now);
	}

	// 재발송 시 이전 미확인 인증번호를 즉시 무효화한다 — 유효한 인증번호는 항상 최대 1개.
	public void expire(LocalDateTime now) {
		this.expiresAt = now;
	}
}
