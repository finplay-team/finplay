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
}
