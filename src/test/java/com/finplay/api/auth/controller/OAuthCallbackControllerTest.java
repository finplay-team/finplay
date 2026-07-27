// OAuth callback의 성공·검증 오류 응답과 state 만료 쿠키 HTTP 계약을 검증한다.
package com.finplay.api.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthCallbackService;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OAuthCallbackController.class)
@Import({OAuthStateCookieFactory.class, SecurityConfig.class})
@TestPropertySource(properties = "oauth.state-cookie-secure=false")
class OAuthCallbackControllerTest {

	private static final String CODE = "authorization-code";
	private static final String STATE = "state-value_123";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OAuthCallbackService callbackService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@ParameterizedTest
	@MethodSource("successfulCallbacks")
	@DisplayName("지원 provider callback은 200 TokenResponse와 정확한 callback Path의 만료 쿠키를 반환한다")
	void callbackReturnsTokensAndExpiresStateCookie(
		String provider, String expectedCookiePath) throws Exception {
		TokenResponse response = new TokenResponse("access-token", "refresh-token", 3600L, 1209600L);
		given(callbackService.callback(provider, CODE, STATE, STATE)).willReturn(response);

		mockMvc.perform(get("/api/auth/oauth/{provider}/callback", provider)
			.param("code", CODE)
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1209600))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("oauth_state=")))
			.andExpect(
				header().string(
					HttpHeaders.SET_COOKIE, containsString("; Path=" + expectedCookiePath)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; SameSite=Lax")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("; Secure"))));

		verify(callbackService).callback(provider, CODE, STATE, STATE);
	}

	@ParameterizedTest
	@MethodSource("validationErrorCallbacks")
	@DisplayName("state 누락·불일치와 code 누락은 400 오류 본문과 정확한 callback Path의 만료 쿠키를 반환한다")
	void callbackReturnsValidationErrorAndExpiresStateCookie(
		String code, String queryState, String cookieState) throws Exception {
		given(callbackService.callback("kakao", code, queryState, cookieState))
			.willThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		var request = get("/api/auth/oauth/kakao/callback");
		if (code != null) {
			request.param("code", code);
		}
		if (queryState != null) {
			request.param("state", queryState);
		}
		if (cookieState != null) {
			request.cookie(new Cookie("oauth_state", cookieState));
		}

		mockMvc.perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("oauth_state=")))
			.andExpect(
				header().string(
					HttpHeaders.SET_COOKIE,
					containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; SameSite=Lax")));
	}

	@Test
	@DisplayName("UTF-8 state를 query와 cookie에서 동일하게 수신해 서비스에 그대로 전달한다")
	void callbackPassesEqualUtf8StateToService() throws Exception {
		String utf8State = "상태-검증-한글";
		TokenResponse response = new TokenResponse("access-token", "refresh-token", 3600L, 1209600L);
		given(callbackService.callback("naver", CODE, utf8State, utf8State)).willReturn(response);

		mockMvc.perform(get("/api/auth/oauth/naver/callback")
			.param("code", CODE)
			.param("state", utf8State)
			.cookie(new Cookie("oauth_state", utf8State)))
			.andExpect(status().isOk());

		verify(callbackService).callback("naver", CODE, utf8State, utf8State);
	}

	@Test
	@DisplayName("reauth 분기 callback은 200 ReauthTokenResponse와 callback Path의 만료 쿠키를 반환한다")
	void callbackReturnsReauthTokenAndExpiresStateCookie() throws Exception {
		ReauthTokenResponse response = new ReauthTokenResponse("raw-reauth-token", 300L);
		given(callbackService.callback("kakao", CODE, STATE, STATE)).willReturn(response);

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", CODE)
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reauthToken").value("raw-reauth-token"))
			.andExpect(jsonPath("$.expiresInSeconds").value(300))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")));

		verify(callbackService).callback("kakao", CODE, STATE, STATE);
	}

	@Test
	@DisplayName("reauth 분기 실패는 403 REAUTHENTICATION_FAILED 본문과 state 만료 쿠키를 반환한다")
	void callbackReturnsReauthenticationFailedAndExpiresStateCookie() throws Exception {
		given(callbackService.callback("kakao", CODE, STATE, STATE))
			.willThrow(new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", CODE)
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_FAILED"))
			.andExpect(jsonPath("$.error.message").value("재인증에 실패했습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")));
	}

	@Test
	@DisplayName("사용자 취소 error query는 400 인가 실패 본문과 state 만료 쿠키를 반환한다")
	void callbackReturnsAuthorizationFailureForProviderErrorQuery() throws Exception {
		given(callbackService.callback("kakao", null, STATE, STATE, "access_denied"))
			.willThrow(new BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED));

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("error", "access_denied")
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("OAUTH_AUTHORIZATION_FAILED"))
			.andExpect(jsonPath("$.error.message")
				.value("OAuth 인가가 취소되었거나 유효하지 않습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")));
	}

	private static Stream<Arguments> successfulCallbacks() {
		return Stream.of(
			Arguments.of("kakao", "/api/auth/oauth/kakao/callback"),
			Arguments.of("naver", "/api/auth/oauth/naver/callback"));
	}

	private static Stream<Arguments> validationErrorCallbacks() {
		return Stream.of(
			Arguments.of(null, STATE, STATE),
			Arguments.of(CODE, null, STATE),
			Arguments.of(CODE, STATE, null),
			Arguments.of(CODE, STATE, "different-state"));
	}
}
