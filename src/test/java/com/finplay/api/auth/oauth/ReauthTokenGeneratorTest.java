// 재인증 토큰 원문 생성기의 바이트 길이·인코딩 형식·유일성을 단위 테스트한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReauthTokenGeneratorTest {

	@Test
	void generateUsesThirtyTwoRandomBytesEncodedAsBase64UrlWithoutPadding() {
		SecureRandom random = mock(SecureRandom.class);
		doAnswer(invocation -> {
			byte[] bytes = invocation.getArgument(0);
			assertThat(bytes).hasSize(32);
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte)i;
			}
			return null;
		}).when(random).nextBytes(any(byte[].class));

		String token = new ReauthTokenGenerator(random).generate();

		assertThat(token)
			.doesNotContain("+", "/", "=")
			.matches("^[A-Za-z0-9_-]+$");
	}

	@Test
	void generateProducesDifferentRawTokensAcrossCalls() {
		ReauthTokenGenerator generator = new ReauthTokenGenerator(new SecureRandom());

		Set<String> tokens = new HashSet<>();
		IntStream.range(0, 20).forEach(i -> tokens.add(generator.generate()));

		assertThat(tokens).hasSize(20);
	}
}
