// OAuth state 쿠키의 값과 callback 경로별 보안 속성 계약을 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseCookie;

class OAuthStateCookieFactoryTest {

	@ParameterizedTest
	@MethodSource("providerCallbackPaths")
	@DisplayName("state 쿠키는 provider callback 경로에 한정된 10분 HttpOnly SameSite Lax 보안 쿠키다")
	void createReturnsSecureCookieForProviderCallback(
		OAuthProviderName provider, String expectedPath) {
		OAuthStateCookieFactory factory = new OAuthStateCookieFactory(true);

		ResponseCookie cookie = factory.create(provider, "state-value_123");

		assertThat(cookie.getName()).isEqualTo("oauth_state");
		assertThat(cookie.getValue()).isEqualTo("state-value_123");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.isSecure()).isTrue();
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.getPath()).isEqualTo(expectedPath);
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(600));
		assertThat(cookie.toString())
			.startsWith("oauth_state=state-value_123")
			.contains("; Path=" + expectedPath)
			.contains("; Max-Age=600")
			.contains("; Secure")
			.contains("; HttpOnly")
			.contains("; SameSite=Lax");
	}

	@Test
	@DisplayName("local과 test에서 secure를 명시적으로 false로 설정하면 Secure 속성을 사용하지 않는다")
	void createOmitsSecureAttributeWhenExplicitlyDisabled() {
		OAuthStateCookieFactory factory = new OAuthStateCookieFactory(false);

		ResponseCookie cookie = factory.create(OAuthProviderName.KAKAO, "state-value_123");

		assertThat(cookie.isSecure()).isFalse();
		assertThat(cookie.toString())
			.startsWith("oauth_state=state-value_123")
			.contains("; Path=/api/auth/oauth/kakao/callback")
			.contains("; Max-Age=600")
			.contains("; HttpOnly")
			.contains("; SameSite=Lax")
			.doesNotContain("; Secure");
	}

	private static Stream<Arguments> providerCallbackPaths() {
		return Stream.of(
			Arguments.of(OAuthProviderName.KAKAO, "/api/auth/oauth/kakao/callback"),
			Arguments.of(OAuthProviderName.NAVER, "/api/auth/oauth/naver/callback"));
	}
}
