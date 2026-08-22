// OAuth 인가 컨트롤러의 redirect와 state 쿠키 및 오류 응답 계약을 검증한다.
package com.finplay.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.state.OAuthStateClaims;
import com.finplay.api.domain.auth.oauth.state.OAuthStateCookieFactory;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.domain.auth.service.OAuthAuthorizationService;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.net.URI;
import java.util.Optional;
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

	@Test
	@DisplayName("purpose=login을 명시해도 미인증 요청은 회귀 없이 302로 리다이렉트된다")
	void authorizeWithExplicitLoginPurposeRedirectsWithoutAuthentication() throws Exception {
		given(authorizationService.authorize("kakao"))
			.willReturn(new OAuthAuthorizationResult(
				OAuthProviderName.KAKAO,
				URI.create("https://kauth.kakao.com/oauth/authorize?response_type=code&state=state-value_123"),
				"state-value_123"));

		mockMvc.perform(get("/api/auth/oauth/kakao/authorize?purpose=login"))
			.andExpect(status().isFound());
	}

	@Test
	@DisplayName("purpose=reauth 요청을 Authorization 헤더 없이 호출하면 401 UNAUTHORIZED다")
	void authorizeReauthWithoutAuthenticationReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/auth/oauth/kakao/authorize?purpose=reauth"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("purpose=reauth 요청을 인증하고 호출하면 200과 authorizationUri 본문을 받는다")
	void authorizeReauthWithAuthenticationReturnsAuthorizationUri() throws Exception {
		given(jwtTokenProvider.parseAccessToken("valid-access-token"))
			.willReturn(Optional.of(new AuthenticatedUser(42L, "USER")));
		given(authorizationService.authorizeForReauth("kakao", 42L))
			.willReturn(new OAuthAuthorizationResult(
				OAuthProviderName.KAKAO,
				URI.create("https://kauth.kakao.com/oauth/authorize?response_type=code&state=reauth-state"),
				"reauth-state"));

		mockMvc.perform(get("/api/auth/oauth/kakao/authorize?purpose=reauth")
			.header(HttpHeaders.AUTHORIZATION, "Bearer valid-access-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorizationUri")
				.value("https://kauth.kakao.com/oauth/authorize?response_type=code&state=reauth-state"));
	}

	@Test
	@DisplayName("purpose가 reauth도 login도 아닌 값이면 인증해도 400 VALIDATION_ERROR다")
	void authorizeWithUnknownPurposeReturnsValidationError() throws Exception {
		given(jwtTokenProvider.parseAccessToken("valid-access-token"))
			.willReturn(Optional.of(new AuthenticatedUser(42L, "USER")));

		mockMvc.perform(get("/api/auth/oauth/kakao/authorize?purpose=foo")
			.header(HttpHeaders.AUTHORIZATION, "Bearer valid-access-token"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	@DisplayName("authorizeReauth는 principal.userId()를 실제 OAuthStateGenerator가 만든 state에 REAUTH로 정확히 묶는다")
	void authorizeReauthBindsAuthenticatedUserIdToStateClaims() throws Exception {
		long userId = 77L;
		OAuthStateGenerator realStateGenerator = new OAuthStateGenerator(
			"test-oauth-state-secret-that-is-at-least-32-bytes");
		given(jwtTokenProvider.parseAccessToken("valid-access-token"))
			.willReturn(Optional.of(new AuthenticatedUser(userId, "USER")));
		// eq(userId)로 인자를 고정해 controller가 principal.userId() 이외의 값을 넘기면 스터빙이 매치되지 않아 실패한다.
		given(authorizationService.authorizeForReauth(eq("kakao"), eq(userId)))
			.willAnswer(invocation -> {
				String state = realStateGenerator.generate(OAuthPurpose.REAUTH, userId);
				return new OAuthAuthorizationResult(
					OAuthProviderName.KAKAO,
					URI.create("https://kauth.kakao.com/oauth/authorize?response_type=code&state=" + state),
					state);
			});

		String setCookieHeader = mockMvc.perform(get("/api/auth/oauth/kakao/authorize?purpose=reauth")
			.header(HttpHeaders.AUTHORIZATION, "Bearer valid-access-token"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getHeader(HttpHeaders.SET_COOKIE);
		String stateValue = extractCookieValue(setCookieHeader, "oauth_state");

		assertThat(realStateGenerator.verify(stateValue))
			.isEqualTo(new OAuthStateClaims(OAuthPurpose.REAUTH, userId));
	}

	private static String extractCookieValue(String setCookieHeader, String cookieName) {
		String prefix = cookieName + "=";
		String firstAttribute = setCookieHeader.split(";", 2)[0];
		assertThat(firstAttribute).startsWith(prefix);
		return firstAttribute.substring(prefix.length());
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
