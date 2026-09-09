package com.finplay.api.domain.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class Sha256BcryptPasswordEncoder implements PasswordEncoder {

	private static final String ENCODING_PREFIX = "{sha256-bcrypt}";
	private static final String DIGEST_ALGORITHM = "SHA-256";

	private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

	@Override
	public String encode(CharSequence rawPassword) {
		if (rawPassword == null) {
			throw new IllegalArgumentException("비밀번호는 null일 수 없습니다.");
		}
		return ENCODING_PREFIX + delegate.encode(sha256(rawPassword));
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		if (rawPassword == null || encodedPassword == null || !encodedPassword.startsWith(ENCODING_PREFIX)) {
			return false;
		}
		return delegate.matches(sha256(rawPassword), encodedPassword.substring(ENCODING_PREFIX.length()));
	}

	@Override
	public boolean upgradeEncoding(String encodedPassword) {
		if (encodedPassword == null || !encodedPassword.startsWith(ENCODING_PREFIX)) {
			return true;
		}
		return delegate.upgradeEncoding(encodedPassword.substring(ENCODING_PREFIX.length()));
	}

	private String sha256(CharSequence value) {
		try {
			byte[] digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
				.digest(value.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("비밀번호 SHA-256 계산에 실패했습니다.", ex);
		}
	}
}
