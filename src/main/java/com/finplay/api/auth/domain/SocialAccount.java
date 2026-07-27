// OAuth 공급자 사용자 식별자와 FinPlay 회원의 연결을 영속한다.
package com.finplay.api.auth.domain;

import com.finplay.api.auth.oauth.OAuthProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "social_accounts", uniqueConstraints = @UniqueConstraint(name = "uk_social_accounts_provider_user", columnNames = {
	"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OAuthProviderName provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private SocialAccount(
		User user, OAuthProviderName provider, String providerUserId, LocalDateTime createdAt) {
		this.user = user;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.createdAt = createdAt;
	}

	public static SocialAccount create(
		User user, OAuthProviderName provider, String providerUserId, LocalDateTime createdAt) {
		return new SocialAccount(user, provider, providerUserId, createdAt);
	}
}
