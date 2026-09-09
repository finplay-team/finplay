package com.finplay.api.domain.auth.verification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VerificationCodeHasher {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final byte[] hmacKey;

	public VerificationCodeHasher(String secret) {
		this.hmacKey = secret.getBytes(StandardCharsets.UTF_8);
	}

	public String hmac(String code) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("인증번호 HMAC 계산에 실패했습니다.", ex);
		}
	}
}
