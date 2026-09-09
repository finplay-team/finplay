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

	public static PasswordResetVerification createRejected(String email, LocalDateTime now) {
		return new PasswordResetVerification(email, null, null, null, now);
	}

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
