// 게시물 좋아요 표시·취소 API의 인증, 상태코드 분기(201/200/204), 예외 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.community.controller;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.domain.community.service.CommunityPostLikeOutcome;
import com.finplay.api.domain.community.service.CommunityPostLikeService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommunityPostLikeController.class)
@Import(SecurityConfig.class)
class CommunityPostLikeControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CommunityPostLikeService service;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void likePostReturns201WithBodyWhenNewLikeIsCreated() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.likePost(9L, USER_ID)).thenReturn(
			new CommunityPostLikeOutcome(new CommunityPostLikeResponse(9L, 1L, true), true));

		mockMvc.perform(post("/api/community/posts/9/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(9))
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));

		verify(service).likePost(9L, USER_ID);
	}

	@Test
	void likePostReturns200WithUnchangedStateWhenAlreadyLiked() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.likePost(9L, USER_ID)).thenReturn(
			new CommunityPostLikeOutcome(new CommunityPostLikeResponse(9L, 3L, true), false));

		mockMvc.perform(post("/api/community/posts/9/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(9))
			.andExpect(jsonPath("$.likeCount").value(3))
			.andExpect(jsonPath("$.likedByMe").value(true));

		verify(service).likePost(9L, USER_ID);
	}

	@Test
	void likePostReturns404WhenPostDoesNotExist() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(service).likePost(404L, USER_ID);

		mockMvc.perform(post("/api/community/posts/404/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void likePostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/community/posts/9/likes"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(service);
	}

	@Test
	void unlikePostReturns204WithNoBodyWhenLikeExisted() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doNothing().when(service).unlikePost(9L, USER_ID);

		mockMvc.perform(delete("/api/community/posts/9/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(service).unlikePost(9L, USER_ID);
	}

	// 좋아요한 적 없는 상태에서 취소를 요청해도 서비스는 예외 없이 반환하고(멱등, no-op), 컨트롤러는 동일하게 204를 준다.
	@Test
	void unlikePostReturns204WhenNoLikeExistedToCancel() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doNothing().when(service).unlikePost(9L, USER_ID);

		mockMvc.perform(delete("/api/community/posts/9/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(service).unlikePost(9L, USER_ID);
	}

	@Test
	void unlikePostReturns404WhenPostDoesNotExist() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(service).unlikePost(404L, USER_ID);

		mockMvc.perform(delete("/api/community/posts/404/likes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void unlikePostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(delete("/api/community/posts/9/likes"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(service);
	}
}
