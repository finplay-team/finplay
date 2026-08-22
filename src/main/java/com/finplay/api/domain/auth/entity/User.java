// 회원 계정을 표현하는 엔티티 (이메일·소셜 공통, 가입 트랜잭션에서만 생성)
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
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	// OAuth 전용 가입자의 password_hash에 채우는 자리표시자 — 어떤 원문 비밀번호와도 대조되지 않는다.
	// 값을 밖으로 노출하지 않는다. 외부에서 직접 비교하면 "NULL이면 비밀번호 없음" 같은 잘못된 판정이 재유입된다
	// (PR #118에서 실제로 소셜 전용 계정에 재설정 인증번호가 발송된 결함이 났다 — 이슈 #122).
	// 생성은 createOAuthOnly, 판정은 hasPassword() 하나로만 한다.
	private static final String OAUTH_ONLY_PASSWORD_SENTINEL = "{oauth-only}";

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

	// OAuth 전용 가입자를 만든다 — 비밀번호가 없으므로 자리표시자를 엔티티가 직접 채운다.
	// 호출부가 자리표시자 값을 알 필요도, 넘길 필요도 없다.
	public static User createOAuthOnly(String email, String nickname, LocalDateTime now) {
		return new User(email, OAUTH_ONLY_PASSWORD_SENTINEL, nickname, now);
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
