// 재인증 토큰 원문을 32바이트 SecureRandom 난수의 URL-safe Base64로 생성한다.
package com.finplay.api.domain.auth.oauth.exchange;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class ReauthTokenGenerator {

	private static final int TOKEN_BYTE_LENGTH = 32;
	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SecureRandom secureRandom;

	public ReauthTokenGenerator() {
		this(new SecureRandom());
	}

	ReauthTokenGenerator(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public String generate() {
		byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		return BASE64_URL_ENCODER.encodeToString(randomBytes);
	}
}
