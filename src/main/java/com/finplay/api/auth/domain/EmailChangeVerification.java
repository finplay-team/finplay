// 회원의 이메일 변경 인증번호 발송 상태를 저장하는 엔티티 (원문 미저장, 해시만 보관)
package com.finplay.api.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_change_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailChangeVerification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "new_email", nullable = false)
	private String newEmail;

	@Column(name = "code_hash", nullable = false)
	private String codeHash;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "last_sent_at", nullable = false)
	private LocalDateTime lastSentAt;

	@Column(name = "consumed_at")
	private LocalDateTime consumedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private EmailChangeVerification(
		User user, String newEmail, String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
		this.user = user;
		this.newEmail = newEmail;
		this.codeHash = codeHash;
		this.attemptCount = 0;
		this.expiresAt = expiresAt;
		this.lastSentAt = now;
		this.createdAt = now;
	}

	public static EmailChangeVerification create(
		User user, String newEmail, String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
		return new EmailChangeVerification(user, newEmail, codeHash, expiresAt, now);
	}

	// 재발송 시 같은 회원·같은 새 이메일의 이전 인증번호를 즉시 무효화한다 — 유효한 인증번호는 항상 최대 1개.
	public void expire(LocalDateTime now) {
		this.expiresAt = now;
	}
}
