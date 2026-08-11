// 댓글 생성 API의 인증, 입력 검증, 서비스 위임과 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.community.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.dto.response.PostCommentResponse;
import com.finplay.api.community.service.PostCommentService;
import java.time.LocalDateTime;
import java.util.List;
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

@WebMvcTest(PostCommentController.class)
@Import(SecurityConfig.class)
class PostCommentControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PostCommentService service;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createCommentReturns201WithExactResponseAndPrincipalUserId() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 27, 12, 0, 0, 123456000);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(7L, USER_ID, "comment", null))
			.thenReturn(new PostCommentResponse(9L, "author", "comment", createdAt, null, List.of()));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\",\"authorId\":999}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.commentId").value(9))
			.andExpect(jsonPath("$.authorNickname").value("author"))
			.andExpect(jsonPath("$.content").value("comment"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-27T12:00:00.123456"))
			.andExpect(jsonPath("$.postId").doesNotExist())
			.andExpect(jsonPath("$.authorId").doesNotExist())
			.andExpect(jsonPath("$.parentCommentId").doesNotExist())
			.andExpect(jsonPath("$.replies").isArray())
			.andExpect(jsonPath("$.replies").isEmpty());

		verify(service).createComment(7L, USER_ID, "comment", null);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void createCommentRejectsInvalidContentWithoutCallingService(String scenario, String json) throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void createCommentReturns404WhenServiceCannotFindPost() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(404L, USER_ID, "comment", null))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/community/posts/404/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void createCommentPassesParentCommentIdToServiceWhenPresentInRequest() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 27, 12, 0, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(7L, USER_ID, "reply", 3L))
			.thenReturn(new PostCommentResponse(9L, "author", "reply", createdAt, 3L, List.of()));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"reply\",\"parentCommentId\":3}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.commentId").value(9))
			.andExpect(jsonPath("$.parentCommentId").value(3))
			.andExpect(jsonPath("$.replies").isArray())
			.andExpect(jsonPath("$.replies").isEmpty());

		verify(service).createComment(7L, USER_ID, "reply", 3L);
	}

	@Test
	void createCommentReturns404WhenServiceCannotFindParentComment() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(7L, USER_ID, "reply", 999L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"reply\",\"parentCommentId\":999}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void createCommentReturns400WhenServiceRejectsReplyToReply() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(7L, USER_ID, "reply", 5L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "대댓글에는 답글을 남길 수 없습니다."));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"reply\",\"parentCommentId\":5}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("대댓글에는 답글을 남길 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	// 이슈 #277 / PR #331 리뷰 참고 사항 #2: tombstone된 부모에 답글을 시도하면 서비스가 던진
	// VALIDATION_ERROR가 그대로 400 응답으로 매핑돼야 한다.
	@Test
	void createCommentReturns400WhenServiceRejectsReplyToTombstonedParent() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createComment(7L, USER_ID, "reply", 5L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "삭제된 댓글에는 답글을 남길 수 없습니다."));

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"reply\",\"parentCommentId\":5}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("삭제된 댓글에는 답글을 남길 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void createCommentRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/community/posts/7/comments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(service);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedBearerTokens")
	void createCommentRejectsInvalidOrRefreshTokenWithoutCallingService(String scenario, String token)
		throws Exception {
		when(jwtTokenProvider.parseAccessToken(token)).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verify(jwtTokenProvider).parseAccessToken(token);
		verifyNoInteractions(service);
	}

	@Test
	void getCommentsReturns200WithExactArrayFieldsAndPassesOnlyPostId() throws Exception {
		LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		LocalDateTime secondCreatedAt = firstCreatedAt.plusMinutes(1);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getComments(7L)).thenReturn(List.of(
			new PostCommentResponse(11L, "first", "first comment", firstCreatedAt, null, List.of()),
			new PostCommentResponse(12L, "second", "second comment", secondCreatedAt, null, List.of())));

		mockMvc.perform(get("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].commentId").value(11))
			.andExpect(jsonPath("$[0].authorNickname").value("first"))
			.andExpect(jsonPath("$[0].content").value("first comment"))
			.andExpect(jsonPath("$[0].createdAt").value("2026-07-27T12:00:00"))
			.andExpect(jsonPath("$[0].postId").doesNotExist())
			.andExpect(jsonPath("$[0].authorId").doesNotExist())
			.andExpect(jsonPath("$[1].commentId").value(12))
			.andExpect(jsonPath("$[1].authorNickname").value("second"))
			.andExpect(jsonPath("$[1].content").value("second comment"))
			.andExpect(jsonPath("$[1].createdAt").value("2026-07-27T12:01:00"));

		verify(service).getComments(7L);
	}

	@Test
	void getCommentsReturns200WithNestedRepliesUnderTheirParentAndEmptyRepliesForChildlessParent() throws Exception {
		LocalDateTime parentCreatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		LocalDateTime replyCreatedAt = parentCreatedAt.plusMinutes(1);
		LocalDateTime otherParentCreatedAt = parentCreatedAt.plusMinutes(2);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getComments(7L)).thenReturn(List.of(
			new PostCommentResponse(11L, "first", "parent comment", parentCreatedAt, null, List.of(
				new PostCommentResponse(13L, "replier", "reply comment", replyCreatedAt, 11L, List.of()))),
			new PostCommentResponse(12L, "second", "childless parent", otherParentCreatedAt, null, List.of())));

		mockMvc.perform(get("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].commentId").value(11))
			.andExpect(jsonPath("$[0].parentCommentId").doesNotExist())
			.andExpect(jsonPath("$[0].replies.length()").value(1))
			.andExpect(jsonPath("$[0].replies[0].commentId").value(13))
			.andExpect(jsonPath("$[0].replies[0].authorNickname").value("replier"))
			.andExpect(jsonPath("$[0].replies[0].content").value("reply comment"))
			.andExpect(jsonPath("$[0].replies[0].createdAt").value("2026-07-27T12:01:00"))
			.andExpect(jsonPath("$[0].replies[0].parentCommentId").value(11))
			.andExpect(jsonPath("$[0].replies[0].replies").isArray())
			.andExpect(jsonPath("$[0].replies[0].replies").isEmpty())
			.andExpect(jsonPath("$[1].commentId").value(12))
			.andExpect(jsonPath("$[1].replies").isArray())
			.andExpect(jsonPath("$[1].replies").isEmpty());

		verify(service).getComments(7L);
	}

	// 이슈 #277: tombstone된 부모 댓글은 content·authorNickname이 치환된 값으로 노출되고, replies·
	// parentCommentId 등 나머지 필드는 tombstone 여부와 무관하게 그대로 전달돼야 한다(서비스 계층에서 이미
	// 치환된 PostCommentResponse를 컨트롤러가 그대로 직렬화하는지 확인).
	@Test
	void getCommentsReturns200WithTombstonedParentContentAndAuthorReplacedWhileRepliesAndParentCommentIdAreUnaffected()
		throws Exception {
		LocalDateTime parentCreatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		LocalDateTime replyCreatedAt = parentCreatedAt.plusMinutes(1);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getComments(7L)).thenReturn(List.of(
			new PostCommentResponse(11L, "(삭제됨)", "삭제된 댓글입니다", parentCreatedAt, null, List.of(
				new PostCommentResponse(13L, "replier", "reply comment", replyCreatedAt, 11L, List.of())))));

		mockMvc.perform(get("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].commentId").value(11))
			.andExpect(jsonPath("$[0].authorNickname").value("(삭제됨)"))
			.andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다"))
			.andExpect(jsonPath("$[0].parentCommentId").doesNotExist())
			.andExpect(jsonPath("$[0].replies.length()").value(1))
			.andExpect(jsonPath("$[0].replies[0].commentId").value(13))
			.andExpect(jsonPath("$[0].replies[0].authorNickname").value("replier"))
			.andExpect(jsonPath("$[0].replies[0].content").value("reply comment"))
			.andExpect(jsonPath("$[0].replies[0].parentCommentId").value(11));

		verify(service).getComments(7L);
	}

	@Test
	void getCommentsReturnsEmptyArrayForExistingPostWithoutComments() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getComments(7L)).thenReturn(List.of());

		mockMvc.perform(get("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void getCommentsReturns404WhenServiceCannotFindPost() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getComments(404L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/community/posts/404/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void getCommentsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/community/posts/7/comments"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(service);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedBearerTokens")
	void getCommentsRejectsInvalidOrRefreshTokenWithoutCallingService(String scenario, String token)
		throws Exception {
		when(jwtTokenProvider.parseAccessToken(token)).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/community/posts/7/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verify(jwtTokenProvider).parseAccessToken(token);
		verifyNoInteractions(service);
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of("missing", "{}"),
			Arguments.of("empty", "{\"content\":\"\"}"),
			Arguments.of("blank", "{\"content\":\"   \"}"),
			Arguments.of("overlong", "{\"content\":\"" + "c".repeat(1001) + "\"}"));
	}

	private static Stream<Arguments> rejectedBearerTokens() {
		return Stream.of(
			Arguments.of("expired access token", "expired.access.token"),
			Arguments.of("tampered access token", "tampered.access.token"),
			Arguments.of("refresh token", "refresh.jwt.token"));
	}
}
