// OAuth 공급자 이름 해석과 공급자별 인가 URI 계약을 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OAuthAuthorizationProviderTest {

	@ParameterizedTest
	@ValueSource(strings = {"KAKAO", "kakao", "KaKaO"})
	@DisplayName("카카오 provider 이름은 대소문자와 무관하게 해석한다")
	void providerNameResolvesKakaoIgnoringCase(String value) {
		assertThat(OAuthProviderName.from(value)).contains(OAuthProviderName.KAKAO);
	}

	@ParameterizedTest
	@ValueSource(strings = {"NAVER", "naver", "NaVeR"})
	@DisplayName("네이버 provider 이름은 대소문자와 무관하게 해석한다")
	void providerNameResolvesNaverIgnoringCase(String value) {
		assertThat(OAuthProviderName.from(value)).contains(OAuthProviderName.NAVER);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"google", "github", " "})
	@DisplayName("미지원 provider 이름은 해석하지 않는다")
	void providerNameDoesNotResolveUnsupportedValue(String value) {
		assertThat(OAuthProviderName.from(value)).isEmpty();
	}

	@Test
	@DisplayName("카카오 인가 URI는 endpoint와 필수 파라미터 및 account_email scope를 포함한다")
	void kakaoCreatesAuthorizationUriWithRequiredParameters() {
		KakaoOAuthAuthorizationProvider provider = new KakaoOAuthAuthorizationProvider(
			"kakao-client-id", "https://finplay.example/api/auth/oauth/kakao/callback");

		URI uri = provider.createAuthorizationUri(OAuthProviderName.KAKAO, "state-value_123");

		assertThat(provider.supports(OAuthProviderName.KAKAO)).isTrue();
		assertThat(provider.supports(OAuthProviderName.NAVER)).isFalse();
		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getHost()).isEqualTo("kauth.kakao.com");
		assertThat(uri.getPath()).isEqualTo("/oauth/authorize");
		assertThat(decodedQueryParameters(uri))
			.containsExactlyInAnyOrderEntriesOf(
				Map.of(
					"response_type", "code",
					"client_id", "kakao-client-id",
					"redirect_uri", "https://finplay.example/api/auth/oauth/kakao/callback",
					"state", "state-value_123",
					"scope", "account_email"));
	}

	@Test
	@DisplayName("네이버 인가 URI는 endpoint와 필수 파라미터를 포함하고 scope는 포함하지 않는다")
	void naverCreatesAuthorizationUriWithRequiredParametersWithoutScope() {
		NaverOAuthAuthorizationProvider provider = new NaverOAuthAuthorizationProvider(
			"naver-client-id", "https://finplay.example/api/auth/oauth/naver/callback");

		URI uri = provider.createAuthorizationUri(OAuthProviderName.NAVER, "state-value_123");

		assertThat(provider.supports(OAuthProviderName.NAVER)).isTrue();
		assertThat(provider.supports(OAuthProviderName.KAKAO)).isFalse();
		assertThat(uri.getScheme()).isEqualTo("https");
		assertThat(uri.getHost()).isEqualTo("nid.naver.com");
		assertThat(uri.getPath()).isEqualTo("/oauth2.0/authorize");
		assertThat(decodedQueryParameters(uri))
			.containsExactlyInAnyOrderEntriesOf(
				Map.of(
					"response_type", "code",
					"client_id", "naver-client-id",
					"redirect_uri", "https://finplay.example/api/auth/oauth/naver/callback",
					"state", "state-value_123"));
		assertThat(decodedQueryParameters(uri)).doesNotContainKey("scope");
	}

	@ParameterizedTest
	@ValueSource(strings = {"KAKAO", "NAVER"})
	@DisplayName("Fake provider는 공급자별 callback 경로에 고정 code와 전달받은 state를 포함한다")
	void fakeCreatesProviderCallbackUriWithCodeAndState(String providerName) {
		FakeOAuthAuthorizationProvider provider = new FakeOAuthAuthorizationProvider();
		OAuthProviderName providerNameValue = OAuthProviderName.valueOf(providerName);

		URI uri = provider.createAuthorizationUri(providerNameValue, "state-value_123");

		assertThat(provider.supports(providerNameValue)).isTrue();
		assertThat(uri.getScheme()).isNull();
		assertThat(uri.getHost()).isNull();
		assertThat(uri.getPath())
			.isEqualTo(
				providerNameValue == OAuthProviderName.KAKAO
					? "/api/auth/oauth/kakao/callback"
					: "/api/auth/oauth/naver/callback");
		assertThat(decodedQueryParameters(uri))
			.containsExactlyInAnyOrderEntriesOf(
				Map.of("code", "fake-code", "state", "state-value_123"));
	}

	private Map<String, String> decodedQueryParameters(URI uri) {
		return Arrays.stream(uri.getRawQuery().split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(
				Collectors.toMap(
					parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
					parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
	}
}
