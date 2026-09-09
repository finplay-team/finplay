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

	public void expire(LocalDateTime now) {
		this.expiresAt = now;
	}

	public int incrementAttemptCount() {
		return ++this.attemptCount;
	}

	public void confirm(LocalDateTime now, String tokenHash, LocalDateTime tokenExpiresAt) {
		this.verifiedAt = now;
		this.tokenHash = tokenHash;
		this.tokenExpiresAt = tokenExpiresAt;
	}
}
