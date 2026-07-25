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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseCookie;

class OAuthStateCookieFactoryTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(OAuthStateCookieFactory.class);

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

	@Test
	@DisplayName("secure 설정이 없으면 fail-safe 기본값 true로 보안 쿠키를 만든다")
	void defaultConfigurationCreatesSecureCookie() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			ResponseCookie cookie = context.getBean(OAuthStateCookieFactory.class)
				.create(OAuthProviderName.KAKAO, "state-value_123");

			assertThat(cookie.isSecure()).isTrue();
			assertThat(cookie.toString()).contains("; Secure");
		});
	}

	@Test
	@DisplayName("local 프로필에서 secure를 명시적으로 false로 설정하면 컨텍스트가 활성화된다")
	void localProfileAllowsExplicitlyDisabledSecureAttribute() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=local", "oauth.state-cookie-secure=false")
			.run(context -> {
				assertThat(context).hasNotFailed();

				ResponseCookie cookie = context.getBean(OAuthStateCookieFactory.class)
					.create(OAuthProviderName.KAKAO, "state-value_123");

				assertThat(cookie.isSecure()).isFalse();
				assertThat(cookie.toString()).doesNotContain("; Secure");
			});
	}

	@Test
	@DisplayName("oauth-real 프로필에서 secure를 false로 설정하면 컨텍스트 기동에 실패한다")
	void oauthRealProfileRejectsExplicitlyDisabledSecureAttribute() {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=oauth-real", "oauth.state-cookie-secure=false")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalStateException.class)
					.hasRootCauseMessage(
						"prod 또는 oauth-real 프로필에서는 OAuth state 쿠키의 Secure 속성을 끌 수 없습니다.");
			});
	}

	private static Stream<Arguments> providerCallbackPaths() {
		return Stream.of(
			Arguments.of(OAuthProviderName.KAKAO, "/api/auth/oauth/kakao/callback"),
			Arguments.of(OAuthProviderName.NAVER, "/api/auth/oauth/naver/callback"));
	}
}
