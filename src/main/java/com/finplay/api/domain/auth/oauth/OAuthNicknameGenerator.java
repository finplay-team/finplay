package com.finplay.api.domain.auth.oauth;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class OAuthNicknameGenerator {

	private static final String NICKNAME_PREFIX = "finplay-";
	private static final int RANDOM_BYTE_LENGTH = 6;

	private final SecureRandom secureRandom;

	public OAuthNicknameGenerator() {
		this(new SecureRandom());
	}

	OAuthNicknameGenerator(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	public String generate() {
		byte[] randomBytes = new byte[RANDOM_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		return NICKNAME_PREFIX + HexFormat.of().formatHex(randomBytes);
	}
}
