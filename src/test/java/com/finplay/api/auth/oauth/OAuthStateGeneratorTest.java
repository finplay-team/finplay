// OAuth state 생성기의 서명 페이로드 형식과 검증 왕복 계약을 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OAuthStateGeneratorTest {

	private static final String SECRET = "test-oauth-state-secret-that-is-at-least-32-bytes";

	@Test
	@DisplayName("LOGIN state의 payload는 목적·빈 userId·43자 nonce를 담고 검증 시 그대로 복원된다")
	void generateSignsLoginPurposeWithoutUserId() {
		byte[] bytes = new byte[32];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = (byte)index;
		}
		OAuthStateGenerator generator = new OAuthStateGenerator(new FixedSecureRandom(bytes), SECRET);

		String state = generator.generate(OAuthPurpose.LOGIN, null);

		String[] parts = state.split("\\.");
		assertThat(parts).hasSize(2);
		assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
			.isEqualTo("LOGIN..AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
		assertThat(generator.verify(state)).isEqualTo(new OAuthStateClaims(OAuthPurpose.LOGIN, null));
	}

	@Test
	@DisplayName("실제 난수 생성기의 연속 state는 서로 다르고 REAUTH의 userId를 왕복 복원한다")
	void generateReturnsDifferentValuesAndRestoresReauthClaims() {
		OAuthStateGenerator generator = new OAuthStateGenerator(new SecureRandom(), SECRET);

		String firstState = generator.generate(OAuthPurpose.REAUTH, 42L);
		String secondState = generator.generate(OAuthPurpose.REAUTH, 42L);

		assertThat(secondState).isNotEqualTo(firstState);
		assertThat(generator.verify(firstState)).isEqualTo(new OAuthStateClaims(OAuthPurpose.REAUTH, 42L));
	}

	@Test
	@DisplayName("payload는 그대로 두고 signature만 위조한 state는 REAUTHENTICATION_FAILED로 거부된다")
	void verifyRejectsForgedSignature() {
		OAuthStateGenerator generator = new OAuthStateGenerator(new SecureRandom(), SECRET);
		String state = generator.generate(OAuthPurpose.LOGIN, null);
		String payloadPart = state.split("\\.")[0];
		String forgedState = payloadPart + "." + reverse(state.split("\\.")[1]);

		assertThatThrownBy(() -> generator.verify(forgedState))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));
	}

	@ParameterizedTest
	@MethodSource("malformedStateFormats")
	@DisplayName("state 형식이 구분자 개수·base64 디코딩 오류면 REAUTHENTICATION_FAILED로 거부된다")
	void verifyRejectsMalformedStateFormat(String malformedState) {
		OAuthStateGenerator generator = new OAuthStateGenerator(new SecureRandom(), SECRET);

		assertThatThrownBy(() -> generator.verify(malformedState))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));
	}

	@Test
	@DisplayName("payload 필드 개수가 어긋나면 서명은 유효해도 REAUTHENTICATION_FAILED로 거부된다")
	void verifyRejectsWrongPayloadFieldCount() {
		OAuthStateGenerator generator = new OAuthStateGenerator(new SecureRandom(), SECRET);
		String state = signedStateFor("LOGIN.123");

		assertThatThrownBy(() -> generator.verify(state))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));
	}

	@Test
	@DisplayName("모르는 purpose 문자열은 서명은 유효해도 REAUTHENTICATION_FAILED로 거부된다")
	void verifyRejectsUnknownPurpose() {
		String state = signedStateFor("UNKNOWN.123.nonce-value");

		assertThatThrownBy(() -> new OAuthStateGenerator(new SecureRandom(), SECRET).verify(state))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));
	}

	private static Stream<Arguments> malformedStateFormats() {
		String invalidBase64Payload = "not-valid-base64!!";
		return Stream.of(
			Arguments.of("no-dot-payload"),
			Arguments.of("payload.signature.extra"),
			Arguments.of(invalidBase64Payload + "." + sign(invalidBase64Payload)));
	}

	private static String signedStateFor(String rawPayload) {
		String payload = rawPayload == null ? "LOGIN..nonce-value" : rawPayload;
		String payloadPart = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return payloadPart + "." + sign(payloadPart);
	}

	private static String sign(String payloadPart) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String reverse(String value) {
		return new StringBuilder(value).reverse().toString();
	}

	private static final class FixedSecureRandom extends SecureRandom {

		private final byte[] bytes;

		private FixedSecureRandom(byte[] bytes) {
			this.bytes = Arrays.copyOf(bytes, bytes.length);
		}

		@Override
		public void nextBytes(byte[] target) {
			assertThat(target).hasSize(32);
			System.arraycopy(bytes, 0, target, 0, target.length);
		}
	}
}
