// OAuth callback의 성공(LOGIN·REAUTH 302 리다이렉트)·검증 오류 응답과 state 만료 쿠키, login-exchange·
// reauth-exchange 교환 HTTP 계약을 검증한다.
package com.finplay.api.domain.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.dto.request.LoginExchangeRequest;
import com.finplay.api.domain.auth.dto.request.ReauthExchangeRequest;
import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.oauth.state.OAuthStateCookieFactory;
import com.finplay.api.domain.auth.service.OAuthCallbackService;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(OAuthCallbackController.class)
@Import({OAuthStateCookieFactory.class, SecurityConfig.class})
@TestPropertySource(properties = {
	"oauth.state-cookie-secure=false",
	"oauth.login-redirect-uri=https://www.finplay.site/oauth/callback",
	"oauth.reauth-redirect-uri=https://www.finplay.site/oauth/reauth-callback"
})
class OAuthCallbackControllerTest {

	private static final String CODE = "authorization-code";
	private static final String STATE = "state-value_123";

	// Boot가 만드는 @Autowired 대상이 아니라 이 슬라이스 컨텍스트에 없는 tools.jackson 빈이다 — 다른 테스트들과
	// 같이 직접 만든다(WatchlistIntegrationTest 등).
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OAuthCallbackService callbackService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@ParameterizedTest
	@MethodSource("successfulCallbacks")
	@DisplayName("지원 provider의 LOGIN callback은 302로 프론트 콜백 주소에 교환 코드만 실어 반환하고, "
		+ "정확한 callback Path의 만료 쿠키를 함께 반환한다")
	void callbackRedirectsToFrontendWithExchangeCodeAndExpiresStateCookie(
		String provider, String expectedCookiePath) throws Exception {
		TokenResponse response = new TokenResponse("access-token", "refresh-token", 3600L, 1209600L);
		given(callbackService.callback(provider, CODE, STATE, STATE)).willReturn(response);
		given(callbackService.issueLoginExchangeCode(response)).willReturn("exchange-code-123");

		mockMvc.perform(get("/api/auth/oauth/{provider}/callback", provider)
			.param("code", CODE)
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				startsWith("https://www.finplay.site/oauth/callback?code=")))
			.andExpect(header().string(HttpHeaders.LOCATION, endsWith("exchange-code-123")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("oauth_state=")))
			.andExpect(
				header().string(
					HttpHeaders.SET_COOKIE, containsString("; Path=" + expectedCookiePath)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; SameSite=Lax")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("; Secure"))));

		verify(callbackService).callback(provider, CODE, STATE, STATE);
		verify(callbackService).issueLoginExchangeCode(response);
	}

	@Test
	@DisplayName("login-exchange는 유효한 코드를 소비해 200 TokenResponse를 반환한다")
	void exchangeReturnsTokensForValidCode() throws Exception {
		TokenResponse response = new TokenResponse("access-token", "refresh-token", 3600L, 1209600L);
		given(callbackService.consumeLoginExchangeCode("exchange-code-123")).willReturn(response);

		mockMvc.perform(post("/api/auth/oauth/login-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new LoginExchangeRequest("exchange-code-123"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1209600));
	}

	@ParameterizedTest
	@ValueSource(strings = {"expired-or-already-consumed", "  "})
	@DisplayName("login-exchange는 만료·소비됐거나 공백인 코드에 400 VALIDATION_ERROR를 반환한다")
	void exchangeReturnsValidationErrorForInvalidCode(String code) throws Exception {
		given(callbackService.consumeLoginExchangeCode(code))
			.willThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(post("/api/auth/oauth/login-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new LoginExchangeRequest(code))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
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
		given(callbackService.issueLoginExchangeCode(response)).willReturn("exchange-code-123");

		mockMvc.perform(get("/api/auth/oauth/naver/callback")
			.param("code", CODE)
			.param("state", utf8State)
			.cookie(new Cookie("oauth_state", utf8State)))
			.andExpect(status().isFound());

		verify(callbackService).callback("naver", CODE, utf8State, utf8State);
	}

	@Test
	@DisplayName("reauth 분기 callback은 302로 재인증 프론트 콜백 주소에 교환 코드만 실어 반환하고(reauthToken 원문 없음), "
		+ "callback Path의 만료 쿠키를 함께 반환한다")
	void callbackRedirectsToReauthFrontendWithExchangeCodeAndExpiresStateCookie() throws Exception {
		ReauthTokenResponse response = new ReauthTokenResponse("raw-reauth-token", 300L);
		given(callbackService.callback("kakao", CODE, STATE, STATE)).willReturn(response);
		given(callbackService.issueReauthExchangeCode(response)).willReturn("reauth-exchange-code-123");

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", CODE)
			.param("state", STATE)
			.cookie(new Cookie("oauth_state", STATE)))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				startsWith("https://www.finplay.site/oauth/reauth-callback?code=")))
			.andExpect(header().string(HttpHeaders.LOCATION, endsWith("reauth-exchange-code-123")))
			.andExpect(header().string(HttpHeaders.LOCATION, not(containsString("raw-reauth-token"))))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=0")));

		verify(callbackService).callback("kakao", CODE, STATE, STATE);
		verify(callbackService).issueReauthExchangeCode(response);
	}

	@Test
	@DisplayName("reauth-exchange는 유효한 코드를 소비해 200 ReauthTokenResponse를 반환한다")
	void reauthExchangeReturnsReauthTokenForValidCode() throws Exception {
		ReauthTokenResponse response = new ReauthTokenResponse("raw-reauth-token", 300L);
		given(callbackService.consumeReauthExchangeCode("reauth-exchange-code-123")).willReturn(response);

		mockMvc.perform(post("/api/auth/oauth/reauth-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ReauthExchangeRequest("reauth-exchange-code-123"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reauthToken").value("raw-reauth-token"))
			.andExpect(jsonPath("$.expiresInSeconds").value(300));
	}

	@ParameterizedTest
	@ValueSource(strings = {"expired-or-already-consumed", "  "})
	@DisplayName("reauth-exchange는 만료·소비됐거나 공백인 코드에 400 VALIDATION_ERROR를 반환한다")
	void reauthExchangeReturnsValidationErrorForInvalidCode(String code) throws Exception {
		given(callbackService.consumeReauthExchangeCode(code))
			.willThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(post("/api/auth/oauth/reauth-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ReauthExchangeRequest(code))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
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
