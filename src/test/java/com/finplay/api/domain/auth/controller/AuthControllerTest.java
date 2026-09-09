package com.finplay.api.domain.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

	private static final String EMAIL = "user@finplay.com";
	private static final String NICKNAME = "finplayer";
	private static final String NEW_NICKNAME = "newplayer";
	private static final String REAUTH_TOKEN = "reauth.raw.token";
	private static final String PASSWORD = "password123";
	private static final String NEW_PASSWORD = "new-password456";
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

	@Test
	void updateNicknameReturnsOkWithMemberResponseForEmailUser() throws Exception {
		stubValidAccessToken();
		when(authService.changeNickname(USER_ID, NEW_NICKNAME, PASSWORD, null))
			.thenReturn(new MemberResponse(USER_ID, EMAIL, NEW_NICKNAME, SignupMethod.EMAIL));

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, PASSWORD, null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(USER_ID))
			.andExpect(jsonPath("$.email").value(EMAIL))
			.andExpect(jsonPath("$.nickname").value(NEW_NICKNAME))
			.andExpect(jsonPath("$.signupMethod").value("EMAIL"))
			.andExpect(jsonPath("$.*", hasSize(4)))
			.andExpect(jsonPath("$.currentPassword").doesNotExist())
			.andExpect(jsonPath("$.reauthToken").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		verify(authService).changeNickname(USER_ID, NEW_NICKNAME, PASSWORD, null);
	}

	@Test
	void updateNicknameReturnsOkWithMemberResponseForOAuthUser() throws Exception {
		stubValidAccessToken();
		when(authService.changeNickname(USER_ID, NEW_NICKNAME, null, REAUTH_TOKEN))
			.thenReturn(new MemberResponse(USER_ID, EMAIL, NEW_NICKNAME, SignupMethod.KAKAO));

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, null, REAUTH_TOKEN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value(NEW_NICKNAME))
			.andExpect(jsonPath("$.signupMethod").value("KAKAO"))
			.andExpect(jsonPath("$.*", hasSize(4)))
			.andExpect(jsonPath("$.reauthToken").doesNotExist());

		verify(authService).changeNickname(USER_ID, NEW_NICKNAME, null, REAUTH_TOKEN);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidNicknameRequests")
	void updateNicknameRejectsInvalidNicknameWithoutCallingService(
		String scenario, String nickname) throws Exception {
		stubValidAccessToken();

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(nickname, PASSWORD, null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("nicknameServiceErrors")
	void updateNicknameMapsServiceErrorToCommonErrorFormat(
		String scenario, ErrorCode errorCode, int expectedStatus) throws Exception {
		stubValidAccessToken();
		when(authService.changeNickname(USER_ID, NEW_NICKNAME, PASSWORD, null))
			.thenThrow(new BusinessException(errorCode));

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, PASSWORD, null)))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.error.code").value(errorCode.name()))
			.andExpect(jsonPath("$.error.message").value(errorCode.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).changeNickname(USER_ID, NEW_NICKNAME, PASSWORD, null);
	}

	@Test
	void updateNicknameMapsServiceValidationErrorToBadRequest() throws Exception {
		stubValidAccessToken();
		when(authService.changeNickname(USER_ID, NEW_NICKNAME, null, null))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "이메일 회원은 현재 비밀번호가 필요합니다."));

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, null, null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("이메일 회원은 현재 비밀번호가 필요합니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).changeNickname(USER_ID, NEW_NICKNAME, null, null);
	}

	@Test
	void updateNicknameRejectsMissingAccessTokenWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/auth/me/nickname")
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, PASSWORD, null)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void updateNicknameRejectsRefreshBearerWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + REFRESH_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(nicknameRequestJson(NEW_NICKNAME, PASSWORD, null)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void updatePasswordReturnsOkWithNewTokenPair() throws Exception {
		stubValidAccessToken();
		when(authService.changePassword(USER_ID, PASSWORD, NEW_PASSWORD))
			.thenReturn(new TokenResponse(
				"reissued-access-token", "reissued-refresh-token", 3600L, 1_209_600L));

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, NEW_PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("reissued-access-token"))
			.andExpect(jsonPath("$.refreshToken").value("reissued-refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1_209_600))
			.andExpect(jsonPath("$.*", hasSize(4)))
			.andExpect(jsonPath("$.currentPassword").doesNotExist())
			.andExpect(jsonPath("$.newPassword").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist())
			.andExpect(content().string(not(containsString(PASSWORD))))
			.andExpect(content().string(not(containsString(NEW_PASSWORD))));

		verify(authService).changePassword(USER_ID, PASSWORD, NEW_PASSWORD);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidPasswordChangeRequests")
	void updatePasswordRejectsInvalidRequestWithoutCallingService(
		String scenario, String currentPassword, String newPassword) throws Exception {
		stubValidAccessToken();

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(currentPassword, newPassword)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("boundaryPasswordChangeRequests")
	void updatePasswordAcceptsBoundaryLengthsAndPassesThemToService(
		String scenario, String currentPassword, String newPassword) throws Exception {
		stubValidAccessToken();
		when(authService.changePassword(USER_ID, currentPassword, newPassword))
			.thenReturn(new TokenResponse(
				"reissued-access-token", "reissued-refresh-token", 3600L, 1_209_600L));

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(currentPassword, newPassword)))
			.andExpect(status().isOk());

		verify(authService).changePassword(USER_ID, currentPassword, newPassword);
	}

	@Test
	void updatePasswordPassesShortCurrentPasswordToServiceInsteadOfRejectingItAsBadRequest() throws Exception {
		stubValidAccessToken();
		when(authService.changePassword(USER_ID, SHORT_PASSWORD, NEW_PASSWORD))
			.thenThrow(new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(SHORT_PASSWORD, NEW_PASSWORD)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_FAILED"));

		verify(authService).changePassword(USER_ID, SHORT_PASSWORD, NEW_PASSWORD);
	}

	@Test
	void updatePasswordDoesNotEchoSubmittedPasswordsInValidationErrorResponse() throws Exception {
		stubValidAccessToken();

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, SHORT_PASSWORD)))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(not(containsString(PASSWORD))))
			.andExpect(content().string(not(containsString(SHORT_PASSWORD))));

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("passwordChangeServiceValidationMessages")
	void updatePasswordMapsServiceValidationErrorToBadRequest(
		String scenario, String message) throws Exception {
		stubValidAccessToken();
		when(authService.changePassword(USER_ID, PASSWORD, NEW_PASSWORD))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, message));

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, NEW_PASSWORD)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(message))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).changePassword(USER_ID, PASSWORD, NEW_PASSWORD);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("passwordChangeServiceErrors")
	void updatePasswordMapsServiceErrorToCommonErrorFormat(
		String scenario, ErrorCode errorCode, int expectedStatus) throws Exception {
		stubValidAccessToken();
		when(authService.changePassword(USER_ID, PASSWORD, NEW_PASSWORD))
			.thenThrow(new BusinessException(errorCode));

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, NEW_PASSWORD)))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.error.code").value(errorCode.name()))
			.andExpect(jsonPath("$.error.message").value(errorCode.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).changePassword(USER_ID, PASSWORD, NEW_PASSWORD);
	}

	@Test
	void updatePasswordRejectsMissingAccessTokenWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/auth/me/password")
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, NEW_PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void updatePasswordRejectsRefreshBearerWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		mockMvc.perform(patch("/api/auth/me/password")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + REFRESH_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(passwordRequestJson(PASSWORD, NEW_PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	private String passwordRequestJson(String currentPassword, String newPassword) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (currentPassword != null) {
			request.put("currentPassword", currentPassword);
		}
		if (newPassword != null) {
			request.put("newPassword", newPassword);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidPasswordChangeRequests() {
		return Stream.of(
			Arguments.of("currentPassword 누락", null, NEW_PASSWORD),
			Arguments.of("currentPassword 빈 문자열", "", NEW_PASSWORD),
			Arguments.of("currentPassword 공백", "   ", NEW_PASSWORD),
			Arguments.of("currentPassword 101자", "c".repeat(101), NEW_PASSWORD),
			Arguments.of("newPassword 누락", PASSWORD, null),
			Arguments.of("newPassword 빈 문자열", PASSWORD, ""),
			Arguments.of("newPassword 공백", PASSWORD, "       "),
			Arguments.of("newPassword 7자", PASSWORD, "1234567"),
			Arguments.of("newPassword 101자", PASSWORD, "n".repeat(101)));
	}

	private static Stream<Arguments> boundaryPasswordChangeRequests() {
		return Stream.of(
			Arguments.of("newPassword 8자", PASSWORD, "12345678"),
			Arguments.of("newPassword 100자", PASSWORD, "n".repeat(100)),
			Arguments.of("currentPassword 100자", "c".repeat(100), NEW_PASSWORD));
	}

	private static Stream<Arguments> passwordChangeServiceValidationMessages() {
		return Stream.of(
			Arguments.of("OAuth 전용 회원", "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다."),
			Arguments.of("새 비밀번호가 현재와 동일", "새 비밀번호는 현재 비밀번호와 달라야 합니다."));
	}

	private static Stream<Arguments> passwordChangeServiceErrors() {
		return Stream.of(
			Arguments.of("현재 비밀번호 불일치 403", ErrorCode.REAUTHENTICATION_FAILED, 403),
			Arguments.of("주체 미존재 401", ErrorCode.UNAUTHORIZED, 401));
	}

	private String nicknameRequestJson(
		String nickname, String currentPassword, String reauthToken) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (nickname != null) {
			request.put("nickname", nickname);
		}
		if (currentPassword != null) {
			request.put("currentPassword", currentPassword);
		}
		if (reauthToken != null) {
			request.put("reauthToken", reauthToken);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidNicknameRequests() {
		return Stream.of(
			Arguments.of("nickname 누락", null),
			Arguments.of("nickname 빈 문자열", ""),
			Arguments.of("nickname 공백", "   "),
			Arguments.of("nickname 51자", "n".repeat(51)));
	}

	private static Stream<Arguments> nicknameServiceErrors() {
		return Stream.of(
			Arguments.of("재인증 실패 403", ErrorCode.REAUTHENTICATION_FAILED, 403),
			Arguments.of("닉네임 중복 409", ErrorCode.DUPLICATE_RESOURCE, 409));
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
