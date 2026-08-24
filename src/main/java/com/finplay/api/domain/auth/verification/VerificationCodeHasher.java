// 인증번호를 용도별 시크릿으로 HMAC-SHA-256 해싱하는 값 객체
package com.finplay.api.domain.auth.verification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 스프링 빈이 아니라 각 서비스가 자기 @Value 시크릿으로 직접 생성한다 — 용도별 시크릿 소유 관계를 배선으로 강제하기 위함이다(#121 D5).
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
