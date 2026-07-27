// OAuth state에 목적·사용자 ID·난수를 HMAC-SHA-256으로 서명해 생성하고 검증한다.
package com.finplay.api.auth.oauth;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateGenerator {

	private static final int NONCE_BYTE_LENGTH = 32;
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String PART_SEPARATOR = ".";
	private static final String PART_SEPARATOR_REGEX = "\\.";
	private static final int STATE_PART_COUNT = 2;
	private static final int PAYLOAD_FIELD_COUNT = 3;
	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

	private final SecureRandom secureRandom;
	private final byte[] hmacKey;

	public OAuthStateGenerator(
		@Value("${oauth.state-secret}")
		String stateSecret) {
		this(new SecureRandom(), stateSecret);
	}

	OAuthStateGenerator(SecureRandom secureRandom, String stateSecret) {
		this.secureRandom = secureRandom;
		this.hmacKey = stateSecret.getBytes(StandardCharsets.UTF_8);
	}

	public String generate(OAuthPurpose purpose, Long userId) {
		byte[] nonceBytes = new byte[NONCE_BYTE_LENGTH];
		secureRandom.nextBytes(nonceBytes);
		String payload = purpose.name() + PART_SEPARATOR + (userId == null ? "" : userId.toString())
			+ PART_SEPARATOR + BASE64_URL_ENCODER.encodeToString(nonceBytes);
		String payloadPart = BASE64_URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

		return payloadPart + PART_SEPARATOR + sign(payloadPart);
	}

	public OAuthStateClaims verify(String state) {
		if (state == null) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		String[] parts = state.split(PART_SEPARATOR_REGEX, -1);
		if (parts.length != STATE_PART_COUNT) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		byte[] expectedSignature = sign(parts[0]).getBytes(StandardCharsets.UTF_8);
		byte[] actualSignature = parts[1].getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		return parseClaims(parts[0]);
	}

	private OAuthStateClaims parseClaims(String payloadPart) {
		String payload;
		try {
			payload = new String(BASE64_URL_DECODER.decode(payloadPart), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		String[] fields = payload.split(PART_SEPARATOR_REGEX, -1);
		if (fields.length != PAYLOAD_FIELD_COUNT) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		OAuthPurpose purpose = OAuthPurpose.from(fields[0])
			.orElseThrow(() -> new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));

		return new OAuthStateClaims(purpose, parseUserId(fields[1]));
	}

	private Long parseUserId(String value) {
		if (value.isEmpty()) {
			return null;
		}

		try {
			return Long.valueOf(value);
		} catch (NumberFormatException ex) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}
	}

	private String sign(String payloadPart) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
			return BASE64_URL_ENCODER.encodeToString(mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("OAuth state 서명 계산에 실패했습니다.", ex);
		}
	}
}
