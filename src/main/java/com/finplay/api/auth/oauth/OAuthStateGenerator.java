// OAuth CSRF 방어에 사용할 URL-safe 난수 state를 생성한다.
package com.finplay.api.auth.oauth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateGenerator {

	private static final int STATE_BYTE_LENGTH = 32;
	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SecureRandom secureRandom;

	public OAuthStateGenerator() {
		this(new SecureRandom());
	}

	OAuthStateGenerator(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public String generate() {
		byte[] randomBytes = new byte[STATE_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		return BASE64_URL_ENCODER.encodeToString(randomBytes);
	}
}
