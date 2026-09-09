package com.finplay.api.domain.community.controller;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.service.PostCommentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommentController.class)
@Import(SecurityConfig.class)
class CommentControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PostCommentService service;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void deleteCommentReturns204WithNoBodyAndDelegatesToService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doNothing().when(service).deleteComment(USER_ID, 9L);

		mockMvc.perform(delete("/api/community/comments/9")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(service).deleteComment(USER_ID, 9L);
	}

	@Test
	void deleteCommentReturns403WhenServiceRejectsNonAuthor() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(service).deleteComment(USER_ID, 9L);

		mockMvc.perform(delete("/api/community/comments/9")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void deleteCommentReturns404WhenServiceCannotFindComment() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(service).deleteComment(USER_ID, 404L);

		mockMvc.perform(delete("/api/community/comments/404")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void deleteCommentRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(delete("/api/community/comments/9"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(service);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedBearerTokens")
	void deleteCommentRejectsInvalidOrRefreshTokenWithoutCallingService(String scenario, String token)
		throws Exception {
		when(jwtTokenProvider.parseAccessToken(token)).thenReturn(Optional.empty());

		mockMvc.perform(delete("/api/community/comments/9")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verify(jwtTokenProvider).parseAccessToken(token);
		verifyNoInteractions(service);
	}

	private static Stream<Arguments> rejectedBearerTokens() {
		return Stream.of(
			Arguments.of("expired access token", "expired.access.token"),
			Arguments.of("tampered access token", "tampered.access.token"),
			Arguments.of("refresh token", "refresh.jwt.token"));
	}
}
