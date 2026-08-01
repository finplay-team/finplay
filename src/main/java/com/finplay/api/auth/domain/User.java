// 회원 계정을 표현하는 엔티티 (이메일·소셜 공통, 가입 트랜잭션에서만 생성)
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
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	// OAuth 전용 가입자의 password_hash에 채우는 자리표시자 — 어떤 원문 비밀번호와도 대조되지 않는다.
	// password_hash 컬럼은 NULL을 허용하지만 실제 생성 경로는 전부 값을 채우므로, 비밀번호 보유 판정은 hasPassword()로 한다.
	public static final String OAUTH_ONLY_PASSWORD_SENTINEL = "{oauth-only}";

	private static final String DEFAULT_ROLE = "USER";
	private static final String DEFAULT_STATUS = "ACTIVE";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(nullable = false, unique = true)
	private String nickname;

	@Column(nullable = false)
	private String role;

	@Column(nullable = false)
	private String status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private User(String email, String passwordHash, String nickname, LocalDateTime now) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
		this.role = DEFAULT_ROLE;
		this.status = DEFAULT_STATUS;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static User create(String email, String passwordHash, String nickname, LocalDateTime now) {
		return new User(email, passwordHash, nickname, now);
	}

	// 재설정·변경할 비밀번호가 실제로 있는지 — OAuth 전용 가입자는 자리표시자만 갖고 있어 false다.
	public boolean hasPassword() {
		return passwordHash != null && !OAUTH_ONLY_PASSWORD_SENTINEL.equals(passwordHash);
	}

	public void changeNickname(String nickname, LocalDateTime now) {
		this.nickname = nickname;
		this.updatedAt = now;
	}

	public void changeEmail(String newEmail, LocalDateTime now) {
		this.email = newEmail;
		this.updatedAt = now;
	}

	// 파라미터는 원문이 아니라 이미 인코딩된 해시다 — 인코딩은 service 책임이다.
	public void changePassword(String newPasswordHash, LocalDateTime now) {
		this.passwordHash = newPasswordHash;
		this.updatedAt = now;
	}
}
