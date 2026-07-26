// 공개 경로 화이트리스트와 Bearer 인증 경계, 401 공통 오류 포맷을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.RequestIdFilter;
import com.jayway.jsonpath.JsonPath;

// /test/protected는 프로덕션 화이트리스트에 없으므로 anyRequest().authenticated()에 걸린다.
// 테스트 편의로 SecurityConfig의 공개 경로를 넓히지 않는다.
@WebMvcTest(controllers = SecurityConfigTest.ProtectedTestController.class)
@Import({
	SecurityConfigTest.ProtectedTestController.class,
	SecurityConfigTest.TestTokenProviderConfig.class,
	SecurityConfig.class
})
class SecurityConfigTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T00:00:00Z");
	private static final String JWT_SECRET = "test-jwt-secret-that-is-at-least-32-bytes";
	private static final long ACCESS_TOKEN_EXPIRATION_MS = 3_600_000L;
	private static final long REFRESH_TOKEN_EXPIRATION_MS = 1_209_600_000L;
	private static final long USER_ID = 42L;
	private static final String PROTECTED_PATH = "/test/protected";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rejectsProtectedPathWithoutTokenAsUnauthorized() throws Exception {
		expectUnauthorizedWithRequestId(get(PROTECTED_PATH));
	}

	@Test
	void rejectsProtectedPathWithExpiredTokenAsUnauthorized() throws Exception {
		// 애플리케이션 Clock(FIXED_INSTANT) 기준으로 이미 만료되도록 발급 시각을 앞당긴다.
		Instant issuedBeforeExpiration = FIXED_INSTANT.minusMillis(ACCESS_TOKEN_EXPIRATION_MS).minusSeconds(1);
		String expiredToken = providerAt(issuedBeforeExpiration).issue(USER_ID, "USER").accessToken();

		expectUnauthorizedWithRequestId(bearer(expiredToken));
	}

	@Test
	void rejectsProtectedPathWithTamperedTokenAsUnauthorized() throws Exception {
		String tamperedToken = tamperSignature(validAccessToken());

		expectUnauthorizedWithRequestId(bearer(tamperedToken));
	}

	@Test
	void rejectsProtectedPathWithRefreshTokenAsUnauthorized() throws Exception {
		String refreshToken = providerAt(FIXED_INSTANT).issue(USER_ID, "USER").refreshToken();

		expectUnauthorizedWithRequestId(bearer(refreshToken));
	}

	@ParameterizedTest(name = "Authorization: {0}")
	@ValueSource(strings = {"Bearer", "Bearer ", "Bearer    "})
	void rejectsBearerHeaderWithoutTokenValue(String authorizationHeader) throws Exception {
		expectUnauthorizedWithRequestId(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, authorizationHeader));
	}

	@ParameterizedTest(name = "Authorization: {0}")
	@ValueSource(strings = {"Basic dXNlcjpwYXNzd29yZA==", "bearer token-with-lowercase-scheme", "Token abc"})
	void rejectsNonBearerAuthorizationScheme(String authorizationHeader) throws Exception {
		expectUnauthorizedWithRequestId(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, authorizationHeader));
	}

	@Test
	void rejectsNonHealthActuatorEndpointWithoutToken() throws Exception {
		// 화이트리스트는 GET /actuator/health 하나뿐이다. 나머지 actuator 경로는 보호 대상이어야 한다.
		expectUnauthorizedWithRequestId(get("/actuator/info"));
		expectUnauthorizedWithRequestId(get("/actuator/env"));
		expectUnauthorizedWithRequestId(post("/actuator/health"));
	}

	@Test
	void allowsProtectedPathWithValidAccessToken() throws Exception {
		mockMvc.perform(bearer(validAccessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(42));
	}

	@Test
	void exposesUserIdAndRoleAsAuthenticationPrincipal() throws Exception {
		mockMvc.perform(bearer(validAccessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(42))
			.andExpect(jsonPath("$.role").value("USER"))
			.andExpect(jsonPath("$.principalType").value(AuthenticatedUser.class.getSimpleName()))
			.andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
	}

	// 이 슬라이스에는 auth 컨트롤러가 없으므로 공개 경로는 401이 아니라 404(NOT_FOUND)로 끝나야 한다.
	// 401이면 화이트리스트가 깨진 것이고, 404면 Security를 통과해 디스패처까지 도달했다는 뜻이다.
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"/api/auth/signup",
		"/api/auth/login",
		"/api/auth/email-verifications",
		"/api/auth/email-verifications/confirm"
	})
	void allowsPublicAuthPathsWithoutToken(String path) throws Exception {
		expectPassesSecurityChain(post(path));
	}

	@Test
	void allowsPublicGetPathsWithoutToken() throws Exception {
		expectPassesSecurityChain(get("/api/auth/oauth/google/authorize"));
		expectPassesSecurityChain(get("/actuator/health"));
	}

	@Test
	void ignoresInvalidTokenOnPublicPath() throws Exception {
		String tamperedToken = tamperSignature(validAccessToken());

		expectPassesSecurityChain(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken));
		expectPassesSecurityChain(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));
	}

	private void expectUnauthorizedWithRequestId(RequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request)
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andReturn();

		// D2 회귀 방지 — RequestIdFilter가 Security 체인보다 먼저 실행되어야 헤더와 본문 requestId가 같아진다.
		String headerRequestId = result.getResponse().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
		String bodyRequestId = JsonPath.read(result.getResponse().getContentAsString(), "$.error.requestId");
		assertThat(headerRequestId).isNotBlank();
		assertThat(bodyRequestId).isEqualTo(headerRequestId);
	}

	private void expectPassesSecurityChain(RequestBuilder request) throws Exception {
		mockMvc.perform(request)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	private static RequestBuilder bearer(String token) {
		return get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private static String validAccessToken() {
		return providerAt(FIXED_INSTANT).issue(USER_ID, "USER").accessToken();
	}

	private static JwtTokenProvider providerAt(Instant instant) {
		return new JwtTokenProvider(JWT_SECRET, ACCESS_TOKEN_EXPIRATION_MS, REFRESH_TOKEN_EXPIRATION_MS,
			Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static String tamperSignature(String token) {
		int signatureStart = token.lastIndexOf('.') + 1;
		String signature = token.substring(signatureStart);
		char firstChar = signature.charAt(0);
		char replacement = firstChar == 'A' ? 'B' : 'A';
		return token.substring(0, signatureStart) + replacement + signature.substring(1);
	}

	@TestConfiguration
	static class TestTokenProviderConfig {

		@Bean
		JwtTokenProvider jwtTokenProvider() {
			return providerAt(FIXED_INSTANT);
		}
	}

	@RestController
	static class ProtectedTestController {

		@GetMapping(PROTECTED_PATH)
		Map<String, Object> protectedResource(
			@AuthenticationPrincipal
			AuthenticatedUser principal,
			Authentication authentication) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("userId", principal.userId());
			body.put("role", principal.role());
			body.put("principalType", authentication.getPrincipal().getClass().getSimpleName());
			body.put("authorities", authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.toList());
			return body;
		}
	}
}
