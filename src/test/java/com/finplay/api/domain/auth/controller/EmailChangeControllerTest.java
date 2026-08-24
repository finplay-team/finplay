// 이메일 변경 인증번호 발송 컨트롤러의 202 응답·검증 오류·인증·서비스 예외 매핑을 검증하는 @WebMvcTest 슬라이스 테스트 (ADR-0003)
package com.finplay.api.domain.auth.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.exception.EmailChangeConflictException;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.service.EmailChangeService;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// 대상 경로가 공개 화이트리스트에 없어 실제 Security 체인을 태워 Bearer 인증 계약까지 함께 검증한다.
@WebMvcTest(EmailChangeController.class)
@Import(SecurityConfig.class)
class EmailChangeControllerTest {

	private static final String NEW_EMAIL = "new@finplay.com";
	private static final String CURRENT_PASSWORD = "password123";
	private static final String REAUTH_TOKEN = "reauth-token";
	private static final String VERIFICATION_CODE = "123456";
	private static final String ACCESS_TOKEN = "access.jwt.token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private EmailChangeService emailChangeService;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void returnsAcceptedWithoutBodyForEmailMemberCurrentPassword() throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/email-changes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(NEW_EMAIL, CURRENT_PASSWORD, null)))
			.andExpect(status().isAccepted())
			.andExpect(content().string(""));

		verify(emailChangeService).requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);
	}

	@Test
	void returnsAcceptedWithoutBodyForOAuthMemberReauthToken() throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/email-changes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(NEW_EMAIL, null, REAUTH_TOKEN)))
			.andExpect(status().isAccepted())
			.andExpect(content().string(""));

		verify(emailChangeService).requestEmailChange(eq(USER_ID), eq(NEW_EMAIL), isNull(), eq(REAUTH_TOKEN));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidNewEmailRequests")
	void returnsValidationErrorForInvalidNewEmailWithoutCallingService(String scenario, String newEmail)
		throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/email-changes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(newEmail, CURRENT_PASSWORD, null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailChangeService);
	}

	@Test
	void returnsUnauthorizedWithoutAuthorizationHeader() throws Exception {
		mockMvc.perform(post("/api/auth/email-changes")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(NEW_EMAIL, CURRENT_PASSWORD, null)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailChangeService);
	}

	@ParameterizedTest(name = "서비스 {1} 예외는 {2}로 매핑된다")
	@MethodSource("serviceErrors")
	void mapsServiceExceptionToExpectedHttpStatus(String scenario, ErrorCode errorCode, int expectedStatus)
		throws Exception {
		stubValidAccessToken();
		doThrow(new BusinessException(errorCode))
			.when(emailChangeService)
			.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);

		mockMvc.perform(post("/api/auth/email-changes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(NEW_EMAIL, CURRENT_PASSWORD, null)))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.error.code").value(errorCode.name()))
			.andExpect(jsonPath("$.error.message").value(errorCode.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(emailChangeService).requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);
	}

	@Test
	void confirmReturnsOkWithUpdatedMemberResponse() throws Exception {
		stubValidAccessToken();
		MemberResponse memberResponse = new MemberResponse(USER_ID, NEW_EMAIL, "finplayer", SignupMethod.EMAIL);
		when(authService.confirmEmailChange(USER_ID, NEW_EMAIL, VERIFICATION_CODE)).thenReturn(memberResponse);

		mockMvc.perform(post("/api/auth/email-changes/confirm")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmRequestJson(NEW_EMAIL, VERIFICATION_CODE)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(USER_ID))
			.andExpect(jsonPath("$.email").value(NEW_EMAIL))
			.andExpect(jsonPath("$.nickname").value("finplayer"))
			.andExpect(jsonPath("$.signupMethod").value("EMAIL"));

		verify(authService).confirmEmailChange(USER_ID, NEW_EMAIL, VERIFICATION_CODE);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidConfirmRequests")
	void confirmReturnsValidationErrorForInvalidRequestWithoutCallingService(
		String scenario, String newEmail, String code) throws Exception {
		stubValidAccessToken();

		mockMvc.perform(post("/api/auth/email-changes/confirm")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmRequestJson(newEmail, code)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void confirmReturnsUnauthorizedWithoutAuthorizationHeader() throws Exception {
		mockMvc.perform(post("/api/auth/email-changes/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmRequestJson(NEW_EMAIL, VERIFICATION_CODE)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.UNAUTHORIZED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@ParameterizedTest(name = "서비스 {0} 예외는 {1}로 매핑된다")
	@MethodSource("confirmServiceErrors")
	void confirmMapsServiceExceptionToExpectedHttpStatus(String scenario, int expectedStatus, String expectedCode)
		throws Exception {
		stubValidAccessToken();
		BusinessException exception = "DUPLICATE_RESOURCE".equals(expectedCode)
			? new EmailChangeConflictException()
			: new BusinessException(ErrorCode.valueOf(expectedCode));
		when(authService.confirmEmailChange(USER_ID, NEW_EMAIL, VERIFICATION_CODE)).thenThrow(exception);

		mockMvc.perform(post("/api/auth/email-changes/confirm")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmRequestJson(NEW_EMAIL, VERIFICATION_CODE)))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.error.code").value(expectedCode))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(authService).confirmEmailChange(USER_ID, NEW_EMAIL, VERIFICATION_CODE);
	}

	private String confirmRequestJson(String newEmail, String code) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (newEmail != null) {
			request.put("newEmail", newEmail);
		}
		if (code != null) {
			request.put("code", code);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidConfirmRequests() {
		return Stream.of(
			Arguments.of("newEmail 누락", null, "123456"),
			Arguments.of("newEmail 형식 오류", "not-an-email", "123456"),
			Arguments.of("code 누락", "new@finplay.com", null),
			Arguments.of("code 형식 오류(문자 포함)", "new@finplay.com", "12345a"),
			Arguments.of("code 자릿수 오류", "new@finplay.com", "12345"));
	}

	private static Stream<Arguments> confirmServiceErrors() {
		return Stream.of(
			Arguments.of("EMAIL_VERIFICATION_FAILED", 400, "EMAIL_VERIFICATION_FAILED"),
			Arguments.of("TOO_MANY_REQUESTS", 429, "TOO_MANY_REQUESTS"),
			Arguments.of("DUPLICATE_RESOURCE", 409, "DUPLICATE_RESOURCE"));
	}

	private void stubValidAccessToken() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private String requestJson(String newEmail, String currentPassword, String reauthToken) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (newEmail != null) {
			request.put("newEmail", newEmail);
		}
		if (currentPassword != null) {
			request.put("currentPassword", currentPassword);
		}
		if (reauthToken != null) {
			request.put("reauthToken", reauthToken);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidNewEmailRequests() {
		return Stream.of(
			Arguments.of("newEmail 누락", null),
			Arguments.of("newEmail 형식 오류", "not-an-email"),
			Arguments.of("newEmail 256자", "a".repeat(244) + "@example.com"));
	}

	private static Stream<Arguments> serviceErrors() {
		return Stream.of(
			Arguments.of("REAUTHENTICATION_FAILED", ErrorCode.REAUTHENTICATION_FAILED, 403),
			Arguments.of("DUPLICATE_RESOURCE", ErrorCode.DUPLICATE_RESOURCE, 409),
			Arguments.of("TOO_MANY_REQUESTS", ErrorCode.TOO_MANY_REQUESTS, 429));
	}
}
