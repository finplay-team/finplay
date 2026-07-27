// OAuth callback의 provider 해석, state 검증, 공급자 호출 순서를 단위 테스트한다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finplay.api.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthCallbackProvider;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthPurpose;
import com.finplay.api.auth.oauth.OAuthStateGenerator;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthCallbackServiceTest {

	private static final String AUTHORIZATION_CODE = "authorization-code";
	private static final OAuthStateGenerator STATE_GENERATOR = new OAuthStateGenerator(
		"test-oauth-state-secret-that-is-at-least-32-bytes");
	private static final String ASCII_STATE = STATE_GENERATOR.generate(OAuthPurpose.LOGIN, null);

	@Mock
	private OAuthCallbackProvider kakaoProvider;

	@Mock
	private OAuthCallbackProvider naverProvider;

	@Mock
	private AuthService authService;

	private OAuthCallbackService callbackService;

	@BeforeEach
	void setUp() {
		callbackService = new OAuthCallbackService(
			List.of(kakaoProvider, naverProvider), authService, STATE_GENERATOR);
	}

	@ParameterizedTest
	@MethodSource("supportedProviders")
	@DisplayName("KAKAO와 NAVER 경로 값을 대소문자와 무관하게 해석해 해당 공급자와 AuthService를 호출한다")
	void callbackResolvesSupportedProvider(
		String rawProvider,
		OAuthProviderName provider) {
		OAuthCallbackProvider selectedProvider = provider == OAuthProviderName.KAKAO ? kakaoProvider : naverProvider;
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "member@example.com");
		TokenResponse expected = tokenResponse();
		given(kakaoProvider.supports(provider)).willReturn(kakaoProvider == selectedProvider);
		if (kakaoProvider != selectedProvider) {
			given(naverProvider.supports(provider)).willReturn(true);
		}
		given(selectedProvider.fetchUser(AUTHORIZATION_CODE, ASCII_STATE)).willReturn(oauthUser);
		given(authService.oauthLogin(provider, oauthUser)).willReturn(expected);

		Object actual = callbackService.callback(rawProvider, AUTHORIZATION_CODE, ASCII_STATE, ASCII_STATE);

		assertThat(actual).isEqualTo(expected);
		verify(selectedProvider).fetchUser(AUTHORIZATION_CODE, ASCII_STATE);
		verify(authService).oauthLogin(provider, oauthUser);
	}

	@Test
	@DisplayName("query와 cookie가 같아도 서명되지 않은 state는 재인증 실패로 거부한다")
	void callbackRejectsUnsignedStateThatMatchesCookie() {
		String utf8State = "상태-검증-🔐";

		assertThatThrownBy(
			() -> callbackService.callback("kakao", AUTHORIZATION_CODE, utf8State, utf8State))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));

		verifyNoInteractions(kakaoProvider, naverProvider, authService);
	}

	@Test
	@DisplayName("REAUTH purpose state는 claims의 userId와 provider·공급자 사용자 정보를 그대로 AuthService.reauthenticate에 위임한다")
	void callbackDispatchesReauthPurposeToAuthServiceReauthenticate() {
		String reauthState = STATE_GENERATOR.generate(OAuthPurpose.REAUTH, 7L);
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "member@example.com");
		ReauthTokenResponse expected = new ReauthTokenResponse("raw-reauth-token", 300L);
		given(kakaoProvider.supports(OAuthProviderName.KAKAO)).willReturn(true);
		given(kakaoProvider.fetchUser(AUTHORIZATION_CODE, reauthState)).willReturn(oauthUser);
		given(authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser)).willReturn(expected);

		Object actual = callbackService.callback(
			"kakao", AUTHORIZATION_CODE, reauthState, reauthState);

		assertThat(actual).isEqualTo(expected);
		verify(authService).reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser);
		verify(authService, never()).oauthLogin(any(), any());
	}

	@ParameterizedTest
	@MethodSource("tamperedStateAuthorizationErrors")
	@DisplayName("서명은 유효했으나 위조된 state는 인가 취소 error query·공급자 호출보다 먼저 재인증 실패로 거부한다")
	void callbackRejectsTamperedSignedStateBeforeAuthorizationErrorAndFetchUser(
		String authorizationError) {
		String validState = STATE_GENERATOR.generate(OAuthPurpose.REAUTH, 7L);
		String[] parts = validState.split("\\.");
		String tamperedState = parts[0] + "." + new StringBuilder(parts[1]).reverse();

		assertThatThrownBy(() -> callbackService.callback(
			"kakao", AUTHORIZATION_CODE, tamperedState, tamperedState, authorizationError))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED));

		verifyNoInteractions(kakaoProvider, naverProvider, authService);
	}

	private static Stream<Arguments> tamperedStateAuthorizationErrors() {
		return Stream.of(Arguments.of((Object)null), Arguments.of("access_denied"));
	}

	@ParameterizedTest
	@MethodSource("invalidProviderUsers")
	@DisplayName("공급자 사용자 ID와 이메일 오류는 AuthService 호출 전에 차단한다")
	void callbackRejectsInvalidProviderUserBeforeAuthService(
		OAuthUserDto oauthUser, ErrorCode expectedErrorCode) {
		given(kakaoProvider.supports(OAuthProviderName.KAKAO)).willReturn(true);
		given(kakaoProvider.fetchUser(AUTHORIZATION_CODE, ASCII_STATE))
			.willReturn(oauthUser);

		assertThatThrownBy(() -> callbackService.callback(
			"kakao", AUTHORIZATION_CODE, ASCII_STATE, ASCII_STATE))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(expectedErrorCode));

		verifyNoInteractions(authService);
	}

	@Test
	@DisplayName("사용자 취소 error query는 state 검증 뒤 공급자 호출 전에 인가 실패로 거부한다")
	void callbackRejectsAuthorizationErrorAfterStateValidation() {
		assertThatThrownBy(() -> callbackService.callback(
			"kakao", null, ASCII_STATE, ASCII_STATE, "access_denied"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED));

		verifyNoInteractions(kakaoProvider, naverProvider, authService);
	}

	@Test
	@DisplayName("사용자 취소 error query라도 state가 불일치하면 먼저 검증 오류로 거부한다")
	void callbackValidatesStateBeforeAuthorizationError() {
		assertThatThrownBy(() -> callbackService.callback(
			"kakao", null, ASCII_STATE, "different-state", "access_denied"))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(kakaoProvider, naverProvider, authService);
	}

	@ParameterizedTest
	@MethodSource("invalidCallbacks")
	@DisplayName("provider, code, query state, cookie state의 누락과 state 불일치는 공급자 호출 전에 거부한다")
	void callbackRejectsInvalidInputBeforeExternalOrAuthCalls(
		String rawProvider,
		String authorizationCode,
		String queryState,
		String cookieState) {
		assertThatThrownBy(
			() -> callbackService.callback(rawProvider, authorizationCode, queryState, cookieState))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(authService);
		verifyNoInteractions(kakaoProvider, naverProvider);
	}

	private static Stream<Arguments> supportedProviders() {
		return Stream.of(
			Arguments.of("kAkAo", OAuthProviderName.KAKAO),
			Arguments.of("NAVER", OAuthProviderName.NAVER));
	}

	private static Stream<Arguments> invalidCallbacks() {
		return Stream.of(
			Arguments.of("google", AUTHORIZATION_CODE, ASCII_STATE, ASCII_STATE),
			Arguments.of(null, AUTHORIZATION_CODE, ASCII_STATE, ASCII_STATE),
			Arguments.of("kakao", null, ASCII_STATE, ASCII_STATE),
			Arguments.of("kakao", " ", ASCII_STATE, ASCII_STATE),
			Arguments.of("kakao", AUTHORIZATION_CODE, null, ASCII_STATE),
			Arguments.of("kakao", AUTHORIZATION_CODE, " ", ASCII_STATE),
			Arguments.of("kakao", AUTHORIZATION_CODE, ASCII_STATE, null),
			Arguments.of("kakao", AUTHORIZATION_CODE, ASCII_STATE, " "),
			Arguments.of("kakao", AUTHORIZATION_CODE, ASCII_STATE, "different-state"));
	}

	private static Stream<Arguments> invalidProviderUsers() {
		return Stream.of(
			Arguments.of(null, ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				new OAuthUserDto(null, "member@example.com"),
				ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				new OAuthUserDto(" ", "member@example.com"),
				ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				new OAuthUserDto("provider-user-id", null),
				ErrorCode.OAUTH_EMAIL_REQUIRED),
			Arguments.of(
				new OAuthUserDto("provider-user-id", " "),
				ErrorCode.OAUTH_EMAIL_REQUIRED));
	}

	private static TokenResponse tokenResponse() {
		return new TokenResponse("access-token", "refresh-token", 3600L, 1209600L);
	}
}
