// 로컬·테스트 Fake OAuth의 일회용 인가 code와 state 조합을 메모리에서 발급·소비한다.
package com.finplay.api.domain.auth.oauth.exchange;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !oauth-real")
public final class FakeOAuthGrantStore {

	private static final int CODE_BYTE_LENGTH = 32;
	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SecureRandom secureRandom;
	private final Set<FakeOAuthGrant> grants = ConcurrentHashMap.newKeySet();

	public FakeOAuthGrantStore() {
		this(new SecureRandom());
	}

	FakeOAuthGrantStore(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public String issue(OAuthProviderName provider, String state) {
		while (true) {
			byte[] randomBytes = new byte[CODE_BYTE_LENGTH];
			secureRandom.nextBytes(randomBytes);
			String code = BASE64_URL_ENCODER.encodeToString(randomBytes);
			if (grants.add(new FakeOAuthGrant(provider, code, state))) {
				return code;
			}
		}
	}

	public boolean consume(OAuthProviderName provider, String code, String state) {
		return provider != null
			&& code != null
			&& state != null
			&& grants.remove(new FakeOAuthGrant(provider, code, state));
	}

	private record FakeOAuthGrant(OAuthProviderName provider, String code, String state) {
	}
}
