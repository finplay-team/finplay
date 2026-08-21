// 실행 프로필에 따라 Fake 또는 실제 OAuth 공급자 빈만 활성화되는지 검증한다.
package com.finplay.api.domain.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.auth.oauth.exchange.FakeOAuthGrantStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.client.RestClient;

class OAuthProviderProfileTest {

	private static final Map<String, String> REAL_OAUTH_SETTINGS = realOAuthSettings();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withBean(
			PropertySourcesPlaceholderConfigurer.class,
			PropertySourcesPlaceholderConfigurer::new)
		.withUserConfiguration(
			FakeOAuthGrantStore.class,
			FakeOAuthAuthorizationProvider.class,
			FakeKakaoOAuthCallbackProvider.class,
			FakeNaverOAuthCallbackProvider.class,
			KakaoOAuthAuthorizationProvider.class,
			NaverOAuthAuthorizationProvider.class,
			KakaoOAuthCallbackProvider.class,
			NaverOAuthCallbackProvider.class);

	@Test
	@DisplayName("기본 프로필에서는 실제 OAuth 키 없이 Fake 공급자 하나만 활성화된다")
	void defaultProfileWiresOnlyFakeProviderWithoutRealOAuthKeys() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OAuthAuthorizationProvider.class);
			assertThat(context).hasSingleBean(FakeOAuthGrantStore.class);
			assertThat(context.getBean(OAuthAuthorizationProvider.class))
				.isInstanceOf(FakeOAuthAuthorizationProvider.class);
			assertThat(context.getBeansOfType(OAuthCallbackProvider.class))
				.hasSize(2)
				.containsOnlyKeys(
					"fakeKakaoOAuthCallbackProvider",
					"fakeNaverOAuthCallbackProvider");
			assertThat(context.getBeansOfType(OAuthCallbackProvider.class).values())
				.allMatch(FakeOAuthCallbackProvider.class::isInstance);
			assertThat(context).doesNotHaveBean(KakaoOAuthAuthorizationProvider.class);
			assertThat(context).doesNotHaveBean(NaverOAuthAuthorizationProvider.class);
			assertThat(context).doesNotHaveBean(KakaoOAuthCallbackProvider.class);
			assertThat(context).doesNotHaveBean(NaverOAuthCallbackProvider.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"prod", "oauth-real"})
	@DisplayName("prod와 oauth-real 프로필에서는 실제 카카오와 네이버 공급자만 활성화된다")
	void realOAuthProfileWiresOnlyKakaoAndNaverProviders(String profile) {
		contextRunner
			.withPropertyValues(
				profileWith(profile, realOAuthPropertyValues()))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(OAuthAuthorizationProvider.class))
					.hasSize(2)
					.containsOnlyKeys(
						"kakaoOAuthAuthorizationProvider", "naverOAuthAuthorizationProvider");
				assertThat(context).hasSingleBean(KakaoOAuthAuthorizationProvider.class);
				assertThat(context).hasSingleBean(NaverOAuthAuthorizationProvider.class);
				assertThat(context).doesNotHaveBean(FakeOAuthAuthorizationProvider.class);
				assertThat(context.getBeansOfType(OAuthCallbackProvider.class))
					.hasSize(2)
					.containsOnlyKeys(
						"kakaoOAuthCallbackProvider", "naverOAuthCallbackProvider");
				assertThat(context).hasSingleBean(KakaoOAuthCallbackProvider.class);
				assertThat(context).hasSingleBean(NaverOAuthCallbackProvider.class);
				assertThat(context).doesNotHaveBean(FakeOAuthCallbackProvider.class);
			});
	}

	@ParameterizedTest(name = "{0} 누락")
	@MethodSource("requiredOAuthSettings")
	@DisplayName("실제 OAuth 프로필은 여섯 설정 중 하나라도 누락되면 fail-fast한다")
	void realOAuthProfileFailsFastWhenRequiredSettingIsMissing(
		String missingProperty, String expectedMessage) {
		contextRunner
			.withPropertyValues(
				profileWith(
					"oauth-real", realOAuthPropertyValuesExcept(missingProperty)))
			.run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest(name = "{0} 공백")
	@MethodSource("requiredOAuthSettings")
	@DisplayName("실제 OAuth 프로필은 여섯 설정 중 하나라도 공백이면 고정 메시지로 fail-fast한다")
	void realOAuthProfileFailsFastWhenRequiredSettingIsBlank(
		String blankProperty, String expectedMessage) {
		contextRunner
			.withPropertyValues(
				profileWith(
					"oauth-real", realOAuthPropertyValuesWith(blankProperty, " ")))
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalArgumentException.class)
					.hasRootCauseMessage(expectedMessage);
			});
	}

	private static Stream<Arguments> requiredOAuthSettings() {
		return Stream.of(
			Arguments.of(
				"oauth.kakao.client-id", "카카오 OAuth clientId가 설정되지 않았습니다."),
			Arguments.of(
				"oauth.kakao.client-secret", "카카오 OAuth clientSecret이 설정되지 않았습니다."),
			Arguments.of(
				"oauth.kakao.redirect-uri", "카카오 OAuth redirectUri가 설정되지 않았습니다."),
			Arguments.of(
				"oauth.naver.client-id", "네이버 OAuth clientId가 설정되지 않았습니다."),
			Arguments.of(
				"oauth.naver.client-secret", "네이버 OAuth clientSecret이 설정되지 않았습니다."),
			Arguments.of(
				"oauth.naver.redirect-uri", "네이버 OAuth redirectUri가 설정되지 않았습니다."));
	}

	private static String[] realOAuthPropertyValues() {
		return REAL_OAUTH_SETTINGS.entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.toArray(String[]::new);
	}

	private static String[] profileWith(String profile, String[] settings) {
		return Stream.concat(
			Stream.of("spring.profiles.active=" + profile), Stream.of(settings))
			.toArray(String[]::new);
	}

	private static String[] realOAuthPropertyValuesExcept(String excludedProperty) {
		return REAL_OAUTH_SETTINGS.entrySet().stream()
			.filter(entry -> !entry.getKey().equals(excludedProperty))
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.toArray(String[]::new);
	}

	private static String[] realOAuthPropertyValuesWith(String property, String value) {
		Map<String, String> settings = new LinkedHashMap<>(REAL_OAUTH_SETTINGS);
		settings.put(property, value);
		return settings.entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.toArray(String[]::new);
	}

	private static Map<String, String> realOAuthSettings() {
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put("oauth.kakao.client-id", "kakao-client-id");
		settings.put("oauth.kakao.client-secret", "kakao-client-secret");
		settings.put(
			"oauth.kakao.redirect-uri",
			"https://finplay.example/api/auth/oauth/kakao/callback");
		settings.put("oauth.naver.client-id", "naver-client-id");
		settings.put("oauth.naver.client-secret", "naver-client-secret");
		settings.put(
			"oauth.naver.redirect-uri",
			"https://finplay.example/api/auth/oauth/naver/callback");
		return settings;
	}
}
