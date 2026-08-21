// OAuth 공급자와 공급자 사용자 식별자 조합으로 연결 회원을 조회하고 저장한다.
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
