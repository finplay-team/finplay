// OAuth state 생성기의 난수 길이와 URL-safe Base64 인코딩 계약을 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OAuthStateGeneratorTest {

	@Test
	@DisplayName("주입한 32바이트 난수는 padding 없는 43자 URL-safe Base64 state가 된다")
	void generateEncodesThirtyTwoBytesAsUrlSafeBase64WithoutPadding() {
		byte[] bytes = new byte[32];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = (byte)index;
		}
		OAuthStateGenerator generator = new OAuthStateGenerator(new FixedSecureRandom(bytes));

		String state = generator.generate();

		assertThat(state).isEqualTo("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
		assertThat(state).hasSize(43).matches("[A-Za-z0-9_-]{43}");
	}

	@Test
	@DisplayName("실제 난수 생성기의 연속 state는 서로 다르고 모두 URL-safe 43자 형식이다")
	void generateReturnsDifferentUrlSafeValuesOnConsecutiveCalls() {
		OAuthStateGenerator generator = new OAuthStateGenerator();

		String firstState = generator.generate();
		String secondState = generator.generate();

		assertThat(firstState).matches("[A-Za-z0-9_-]{43}");
		assertThat(secondState).matches("[A-Za-z0-9_-]{43}");
		assertThat(secondState).isNotEqualTo(firstState);
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
