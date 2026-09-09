package com.finplay.api.domain.auth.repository;

import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

	Optional<SocialAccount> findByProviderAndProviderUserId(
		OAuthProviderName provider, String providerUserId);

	Optional<SocialAccount> findByUserId(Long userId);
}
