// OAuth 인가 컨트롤러의 redirect와 state 쿠키 및 오류 응답 계약을 검증한다.
package com.finplay.api.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthAuthorizationService;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.net.URI;
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

// 대상 경로가 공개 화이트리스트에 있으므로 실제 Security 체인을 태워 화이트리스트 계약까지 함께 검증한다.
@WebMvcTest(OAuthAuthorizationController.class)
@Import({OAuthStateCookieFactory.class, SecurityConfig.class})
@TestPropertySource(properties = "oauth.state-cookie-secure=false")
class OAuthAuthorizationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OAuthAuthorizationService authorizationService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@ParameterizedTest
	@MethodSource("successfulAuthorizationResponses")
	@DisplayName("지원 provider 인가 요청은 정확한 Location과 callback 경로의 state 쿠키로 302 응답한다")
	void authorizeRedirectsWithStateCookie(
		String rawProvider,
		OAuthProviderName provider,
		String authorizationUri,
		String expectedCallbackPath)
		throws Exception {
		given(authorizationService.authorize(rawProvider))
			.willReturn(new OAuthAuthorizationResult(
				provider, URI.create(authorizationUri), "state-value_123"));

		mockMvc.perform(get("/api/auth/oauth/{provider}/authorize", rawProvider))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION, authorizationUri))
			.andExpect(
				header().string(
					HttpHeaders.SET_COOKIE, containsString("oauth_state=state-value_123")))
			.andExpect(
				header().string(
					HttpHeaders.SET_COOKIE, containsString("; Path=" + expectedCallbackPath)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; Max-Age=600")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("; SameSite=Lax")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("; Secure"))));
	}

	@Test
	@DisplayName("미지원 provider는 400 VALIDATION_ERROR 공통 오류 본문으로 응답한다")
	void authorizeReturnsValidationErrorForUnsupportedProvider() throws Exception {
		given(authorizationService.authorize("google"))
			.willThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(get("/api/auth/oauth/google/authorize"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("요청 값이 올바르지 않습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	private static Stream<Arguments> successfulAuthorizationResponses() {
		return Stream.of(
			Arguments.of(
				"kakao",
				OAuthProviderName.KAKAO,
				"https://kauth.kakao.com/oauth/authorize?response_type=code&state=state-value_123",
				"/api/auth/oauth/kakao/callback"),
			Arguments.of(
				"naver",
				OAuthProviderName.NAVER,
				"https://nid.naver.com/oauth2.0/authorize?response_type=code&state=state-value_123",
				"/api/auth/oauth/naver/callback"));
	}
}
