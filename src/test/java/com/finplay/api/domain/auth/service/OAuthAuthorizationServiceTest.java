// OAuth 인가 서비스의 provider 선택과 state 전달 및 오류 계약을 검증한다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.finplay.api.domain.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.exchange.FakeOAuthGrantStore;
import com.finplay.api.domain.auth.oauth.provider.FakeOAuthAuthorizationProvider;
import com.finplay.api.domain.auth.oauth.provider.OAuthAuthorizationProvider;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthAuthorizationServiceTest {

	@Mock
	private OAuthStateGenerator stateGenerator;

	@ParameterizedTest
	@MethodSource("supportedProviderInputs")
	@DisplayName("provider 이름은 대소문자와 무관하게 선택되고 같은 state가 URI와 결과에 전달된다")
	void authorizeSelectsProviderIgnoringCaseAndSharesStateWithUriAndResult(
		String rawProvider, OAuthProviderName expectedProvider, String expectedPath) {
		given(stateGenerator.generate(OAuthPurpose.LOGIN, null)).willReturn("state-value_123");
		OAuthAuthorizationService service = new OAuthAuthorizationService(
			List.of(fakeProvider()), stateGenerator);

		OAuthAuthorizationResult result = service.authorize(rawProvider);

		assertThat(result.provider()).isEqualTo(expectedProvider);
		assertThat(result.authorizationUri().getPath()).isEqualTo(expectedPath);
		assertThat(queryParameters(result.authorizationUri()).get("code"))
			.matches("^[A-Za-z0-9_-]{43}$");
		assertThat(queryParameters(result.authorizationUri()).get("state"))
			.isEqualTo("state-value_123");
		assertThat(result.state()).isEqualTo("state-value_123");
	}

	@Test
	@DisplayName("미지원 provider는 VALIDATION_ERROR 비즈니스 예외로 거부한다")
	void authorizeFailsWithValidationErrorForUnsupportedProvider() {
		OAuthAuthorizationService service = new OAuthAuthorizationService(
			List.of(fakeProvider()), stateGenerator);

		assertThatThrownBy(() -> service.authorize("google"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	@DisplayName("지원 provider를 처리할 활성 구현이 없으면 구성 오류로 실패한다")
	void authorizeFailsWhenNoActiveProviderSupportsProvider() {
		OAuthAuthorizationProvider unsupportedProvider = new OAuthAuthorizationProvider() {
			@Override
			public boolean supports(OAuthProviderName provider) {
				return false;
			}

			@Override
			public URI createAuthorizationUri(OAuthProviderName provider, String state) {
				throw new AssertionError("지원하지 않는 provider의 URI를 생성하면 안 됩니다.");
			}
		};
		OAuthAuthorizationService service = new OAuthAuthorizationService(
			List.of(unsupportedProvider), stateGenerator);

		assertThatThrownBy(() -> service.authorize("kakao"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("활성화된 OAuth 인가 공급자가 없습니다: KAKAO");
	}

	@Test
	@DisplayName("authorizeForReauth는 REAUTH 목적과 해당 userId로 state를 생성해 결과와 URI에 담는다")
	void authorizeForReauthGeneratesStateWithReauthPurposeAndUserId() {
		given(stateGenerator.generate(OAuthPurpose.REAUTH, 42L)).willReturn("reauth-state-value");
		OAuthAuthorizationService service = new OAuthAuthorizationService(
			List.of(fakeProvider()), stateGenerator);

		OAuthAuthorizationResult result = service.authorizeForReauth("kakao", 42L);

		assertThat(result.provider()).isEqualTo(OAuthProviderName.KAKAO);
		assertThat(result.state()).isEqualTo("reauth-state-value");
		assertThat(queryParameters(result.authorizationUri()).get("state"))
			.isEqualTo("reauth-state-value");
	}

	@Test
	@DisplayName("authorizeForReauth도 미지원 provider는 VALIDATION_ERROR 비즈니스 예외로 거부한다")
	void authorizeForReauthFailsWithValidationErrorForUnsupportedProvider() {
		OAuthAuthorizationService service = new OAuthAuthorizationService(
			List.of(fakeProvider()), stateGenerator);

		assertThatThrownBy(() -> service.authorizeForReauth("google", 42L))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	private static Stream<Arguments> supportedProviderInputs() {
		return Stream.of(
			Arguments.of(
				"KaKaO",
				OAuthProviderName.KAKAO,
				"/api/auth/oauth/kakao/callback"),
			Arguments.of(
				"nAvEr",
				OAuthProviderName.NAVER,
				"/api/auth/oauth/naver/callback"));
	}

	private static FakeOAuthAuthorizationProvider fakeProvider() {
		return new FakeOAuthAuthorizationProvider(new FakeOAuthGrantStore());
	}

	private static Map<String, String> queryParameters(URI uri) {
		return Arrays.stream(uri.getRawQuery().split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(Collectors.toMap(
				parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
				parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
	}
}
