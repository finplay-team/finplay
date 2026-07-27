// 회원가입·로그인·토큰 재발급 응답과 입력 검증, 비즈니스 오류 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.auth.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.domain.SignupMethod;
import com.finplay.api.auth.dto.response.MemberResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.service.AuthService;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

import tools.jackson.databind.ObjectMapper;

// 대상 경로가 공개 화이트리스트에 있으므로 실제 Security 체인을 태워 화이트리스트 계약까지 함께 검증한다.
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

	private static final String EMAIL = "user@finplay.com";
	private static final String NICKNAME = "finplayer";
	private static final String PASSWORD = "password123";
	private static final String SHORT_PASSWORD = "pass123";
	private static final String SIGNUP_TOKEN = "signup-token";
	private static final String ACCESS_TOKEN = "access.jwt.token";
	private static final String REFRESH_TOKEN = "refresh.jwt.token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void signupReturnsCreatedWithAllTokenFields() throws Exception {
		TokenResponse response = new TokenResponse(
			"access-token", "refresh-token", 3600L, 1_209_600L);
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN)).thenReturn(response);

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1_209_600));

		verify(authService).signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void signupRejectsInvalidRequestWithoutCallingService(
		String scenario,
		String email,
		String nickname,
		String password,
		Boolean termsAgreed,
		String signupToken) throws Exception {
		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(email, nickname, password, termsAgreed, signupToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void signupRejectsOverlongVerificationTokenWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, "t".repeat(256))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void signupMapsDuplicateResourceToConflictErrorFormat() throws Exception {
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN))
			.thenThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE));

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.DUPLICATE_RESOURCE.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void signupMapsEmailVerificationRequiredToConflictErrorFormat() throws Exception {
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN))
			.thenThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED));

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.EMAIL_VERIFICATION_REQUIRED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void loginReturnsOkWithTokenPair() throws Exception {
		TokenResponse response = new TokenResponse(
			"login-access-token", "login-refresh-token", 3600L, 1_209_600L);
		when(authService.login(EMAIL, PASSWORD)).thenReturn(response);

		mockMvc.perform(post("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(loginRequestJson(EMAIL, PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("login-access-token"))
			.andExpect(jsonPath("$.refreshToken").value("login-refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1_209_600));

		verify(authService).login(EMAIL, PASSWORD);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidLoginRequests")
	void loginReturnsBadRequestForInvalidRequest(
		String scenario, String email, String password) throws Exception {
		mockMvc.perform(post("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(loginRequestJson(email, password)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void loginReturnsUnauthorizedForInvalidCredentials() throws Exception {
		when(authService.login(EMAIL, PASSWORD))
			.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		mockMvc.perform(post("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(loginRequestJson(EMAIL, PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void loginPassesShortPasswordToServiceInsteadOfRejectingItAsBadRequest() throws Exception {
		// LoginRequest.password에 min을 두지 않은 것은 의도된 설계다(계획 HTTP 계약 표).
		// min을 붙이면 짧은 입력만 400, 나머지는 401로 갈려 응답이 저장된 자격증명의 힌트가 된다.
		when(authService.login(EMAIL, SHORT_PASSWORD))
			.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		mockMvc.perform(post("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(loginRequestJson(EMAIL, SHORT_PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verify(authService).login(EMAIL, SHORT_PASSWORD);
	}

	@Test
	void refreshReturnsOkWithAllTokenFieldsWithoutAuthorizationHeader() throws Exception {
		TokenResponse response = new TokenResponse(
			"rotated-access-token", "rotated-refresh-token", 3600L, 1_209_600L);
		when(authService.refresh(REFRESH_TOKEN)).thenReturn(response);

		mockMvc.perform(post("/api/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(REFRESH_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("rotated-access-token"))
			.andExpect(jsonPath("$.refreshToken").value("rotated-refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1_209_600));

		verify(authService).refresh(REFRESH_TOKEN);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRefreshRequests")
	void refreshRejectsInvalidRequestWithoutCallingService(String scenario, String refreshToken) throws Exception {
		mockMvc.perform(post("/api/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(refreshToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void refreshPassesExactly4096CharactersToService() throws Exception {
		String maximumLengthToken = "r".repeat(4096);
		TokenResponse response = new TokenResponse(
			"maximum-access-token", "maximum-refresh-token", 3600L, 1_209_600L);
		when(authService.refresh(maximumLengthToken)).thenReturn(response);

		mockMvc.perform(post("/api/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(maximumLengthToken)))
			.andExpect(status().isOk());

		verify(authService).refresh(maximumLengthToken);
	}

	@ParameterizedTest(name = "유효 길이 경계 [{index}]")
	@MethodSource("unauthorizedRefreshTokens")
	void refreshMapsServiceUnauthorizedToCommonErrorFormat(String refreshToken) throws Exception {
		when(authService.refresh(refreshToken))
			.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		mockMvc.perform(post("/api/auth/refresh")
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(refreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).refresh(refreshToken);
	}

	@Test
	void logoutReturnsNoContentAndPassesPrincipalUserIdAndRawRefreshToken() throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/logout")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(REFRESH_TOKEN)))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(authService).logout(USER_ID, REFRESH_TOKEN);
	}

	@Test
	void logoutRejectsMissingAccessTokenWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(REFRESH_TOKEN)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void logoutRejectsRefreshBearerWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/auth/logout")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + REFRESH_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(REFRESH_TOKEN)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRefreshRequests")
	void logoutRejectsInvalidRequestWithoutCallingService(String scenario, String refreshToken) throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/logout")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(refreshToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("logoutServiceErrors")
	void logoutMapsServiceErrorToCommonErrorFormat(
		String scenario, ErrorCode errorCode, int expectedStatus) throws Exception {
		stubValidAccessToken();
		doThrow(new BusinessException(errorCode))
			.when(authService)
			.logout(USER_ID, REFRESH_TOKEN);

		mockMvc.perform(post("/api/auth/logout")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(refreshRequestJson(REFRESH_TOKEN)))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.error.code").value(errorCode.name()))
			.andExpect(jsonPath("$.error.message").value(errorCode.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).logout(USER_ID, REFRESH_TOKEN);
	}

	@Test
	void meReturnsOkWithIdEmailNicknameSignupMethodForAuthenticatedUser() throws Exception {
		stubValidAccessToken();
		when(authService.getMe(USER_ID))
			.thenReturn(new MemberResponse(USER_ID, EMAIL, NICKNAME, SignupMethod.EMAIL));

		mockMvc.perform(get("/api/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(USER_ID))
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.nickname").value(NICKNAME))
			.andExpect(jsonPath("$.signupMethod").value("EMAIL"))
			// 민감 필드가 어떤 이름으로도 새지 않도록 응답 키 집합 자체를 4개로 고정한다.
			.andExpect(jsonPath("$.*", hasSize(4)));

		verify(authService).getMe(USER_ID);
	}

	@ParameterizedTest(name = "{0} 가입자")
	@EnumSource(value = SignupMethod.class, names = {"KAKAO", "NAVER"})
	void meReturnsSignupMethodKakaoAndNaverForSocialMembers(SignupMethod signupMethod) throws Exception {
		stubValidAccessToken();
		when(authService.getMe(USER_ID))
			.thenReturn(new MemberResponse(USER_ID, EMAIL, NICKNAME, signupMethod));

		mockMvc.perform(get("/api/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.signupMethod").value(signupMethod.name()));

		verify(authService).getMe(USER_ID);
	}

	@Test
	void meRejectsMissingAccessTokenWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void meRejectsRefreshBearerWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + REFRESH_TOKEN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void meMapsServiceUnauthorizedToCommonErrorFormat() throws Exception {
		stubValidAccessToken();
		when(authService.getMe(USER_ID)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		mockMvc.perform(get("/api/auth/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).getMe(USER_ID);
	}

	private void stubValidAccessToken() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private String loginRequestJson(String email, String password) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (email != null) {
			request.put("email", email);
		}
		if (password != null) {
			request.put("password", password);
		}
		return objectMapper.writeValueAsString(request);
	}

	private String refreshRequestJson(String refreshToken) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (refreshToken != null) {
			request.put("refreshToken", refreshToken);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidRefreshRequests() {
		return Stream.of(
			Arguments.of("refreshToken 누락", null),
			Arguments.of("refreshToken 빈 문자열", ""),
			Arguments.of("refreshToken 공백", "   "),
			Arguments.of("refreshToken 4097자", "r".repeat(4097)));
	}

	private static Stream<String> unauthorizedRefreshTokens() {
		return Stream.of("x", "r".repeat(4096));
	}

	private static Stream<Arguments> logoutServiceErrors() {
		return Stream.of(
			Arguments.of("서비스 401", ErrorCode.UNAUTHORIZED, 401),
			Arguments.of("서비스 403", ErrorCode.FORBIDDEN, 403));
	}

	private static Stream<Arguments> invalidLoginRequests() {
		return Stream.of(
			Arguments.of("email 누락", null, PASSWORD),
			Arguments.of("email 형식 오류", "not-an-email", PASSWORD),
			Arguments.of("email 공백", "   ", PASSWORD),
			Arguments.of("email 256자", "a".repeat(244) + "@example.com", PASSWORD),
			Arguments.of("password 누락", EMAIL, null),
			Arguments.of("password 공백", EMAIL, "   "),
			Arguments.of("password 101자", EMAIL, "p".repeat(101)));
	}

	private String requestJson(
		String email,
		String nickname,
		String password,
		Boolean termsAgreed,
		String signupToken) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (email != null) {
			request.put("email", email);
		}
		if (nickname != null) {
			request.put("nickname", nickname);
		}
		if (password != null) {
			request.put("password", password);
		}
		if (termsAgreed != null) {
			request.put("termsAgreed", termsAgreed);
		}
		if (signupToken != null) {
			request.put("signupVerificationToken", signupToken);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of("email 누락", null, NICKNAME, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("email 형식 오류", "not-an-email", NICKNAME, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("email 256자", "a".repeat(244) + "@example.com", NICKNAME, PASSWORD, true,
				SIGNUP_TOKEN),
			Arguments.of("nickname 누락", EMAIL, null, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("nickname 51자", EMAIL, "n".repeat(51), PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("password 누락", EMAIL, NICKNAME, null, true, SIGNUP_TOKEN),
			Arguments.of("password 7자", EMAIL, NICKNAME, "1234567", true, SIGNUP_TOKEN),
			Arguments.of("password 101자", EMAIL, NICKNAME, "p".repeat(101), true, SIGNUP_TOKEN),
			Arguments.of("termsAgreed 누락", EMAIL, NICKNAME, PASSWORD, null, SIGNUP_TOKEN),
			Arguments.of("termsAgreed false", EMAIL, NICKNAME, PASSWORD, false, SIGNUP_TOKEN),
			Arguments.of("signupVerificationToken 누락", EMAIL, NICKNAME, PASSWORD, true, null));
	}
}
