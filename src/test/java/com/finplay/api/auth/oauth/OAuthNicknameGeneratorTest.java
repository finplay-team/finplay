// OAuth 전용 nickname의 SecureRandom 바이트 길이와 비식별 형식을 단위 테스트한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class OAuthNicknameGeneratorTest {

	@Test
	void generateUsesSixRandomBytesAndLowercaseHexWithoutIdentityData() {
		SecureRandom random = mock(SecureRandom.class);
		doAnswer(invocation -> {
			byte[] bytes = invocation.getArgument(0);
			assertThat(bytes).hasSize(6);
			System.arraycopy(
				new byte[] {0x00, 0x12, (byte)0xab, (byte)0xcd, (byte)0xef, 0x45},
				0,
				bytes,
				0,
				bytes.length);
			return null;
		}).when(random).nextBytes(org.mockito.ArgumentMatchers.any(byte[].class));

		String nickname = new OAuthNicknameGenerator(random).generate();

		assertThat(nickname)
			.isEqualTo("finplay-0012abcdef45")
			.matches("^finplay-[0-9a-f]{12}$")
			.doesNotContain("member@example.com", "provider-user-id");
	}
}
