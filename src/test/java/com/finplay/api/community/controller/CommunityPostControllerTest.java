// 게시글 생성 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.community.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.dto.response.CommunityPostListResponse;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.service.CommunityPostService;
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

@WebMvcTest(CommunityPostController.class)
@Import(SecurityConfig.class)
class CommunityPostControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CommunityPostService service;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createPostReturnsCreatedWithEveryResponseFieldAndPrincipalUserId() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", null, null))
			.thenReturn(new CommunityPostResponse(
				7L, "author", "title", "content", now, now, null, null, null, null, null));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","authorId":999,"userId":999}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(7))
			.andExpect(jsonPath("$.authorNickname").value("author"))
			.andExpect(jsonPath("$.title").value("title"))
			.andExpect(jsonPath("$.content").value("content"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-27T12:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-27T12:00:00"))
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist())
			.andExpect(jsonPath("$.instrumentName").doesNotExist())
			.andExpect(jsonPath("$.imageId").doesNotExist())
			.andExpect(jsonPath("$.imageUrl").doesNotExist());

		verify(service).createPost(USER_ID, "title", "content", null, null);
	}

	@Test
	void createPostPassesInstrumentIdToServiceAndReturnsTagFieldsWhenProvided() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", 9L, null))
			.thenReturn(new CommunityPostResponse(
				7L, "author", "title", "content", now, now, 9L, "BTC", "비트코인", null, null));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","instrumentId":9}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(7))
			.andExpect(jsonPath("$.instrumentId").value(9))
			.andExpect(jsonPath("$.instrumentSymbol").value("BTC"))
			.andExpect(jsonPath("$.instrumentName").value("비트코인"));

		verify(service).createPost(USER_ID, "title", "content", 9L, null);
	}

	@Test
	void createPostReturnsCommonValidationErrorWhenServiceRejectsInstrumentTag() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", 999L, null))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","instrumentId":999}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("존재하지 않거나 비활성인 종목은 태그할 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).createPost(USER_ID, "title", "content", 999L, null);
	}

	@Test
	void createPostPassesImageIdToServiceAndReturnsImageFieldsWhenProvided() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", null, 5L))
			.thenReturn(new CommunityPostResponse(7L, "author", "title", "content", now, now, null, null, null,
				5L, "/api/community/posts/images/5/file"));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","imageId":5}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(7))
			.andExpect(jsonPath("$.imageId").value(5))
			.andExpect(jsonPath("$.imageUrl").value("/api/community/posts/images/5/file"));

		verify(service).createPost(USER_ID, "title", "content", null, 5L);
	}

	@Test
	void createPostReturnsCommonNotFoundErrorWhenServiceRejectsUnknownImageId() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", null, 404L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","imageId":404}
				"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).createPost(USER_ID, "title", "content", null, 404L);
	}

	@Test
	void createPostReturnsCommonForbiddenErrorWhenServiceRejectsImageOwnedByAnotherUser() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", null, 5L))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "본인이 업로드한 이미지만 사용할 수 있습니다."));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","imageId":5}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("본인이 업로드한 이미지만 사용할 수 있습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).createPost(USER_ID, "title", "content", null, 5L);
	}

	@Test
	void createPostReturnsCommonValidationErrorWhenServiceRejectsAlreadyAssignedImageId() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.createPost(USER_ID, "title", "content", null, 5L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "이미 다른 게시물에 사용된 이미지입니다."));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"title","content":"content","imageId":5}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("이미 다른 게시물에 사용된 이미지입니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).createPost(USER_ID, "title", "content", null, 5L);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void createPostRejectsInvalidTextWithoutCallingService(String scenario, String json) throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void createPostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/community/posts")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"title\",\"content\":\"content\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedBearerTokens")
	void createPostRejectsInvalidBearerThroughAccessTokenParserWithoutCallingService(
		String scenario, String bearerToken) throws Exception {
		when(jwtTokenProvider.parseAccessToken(bearerToken)).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"title\",\"content\":\"content\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(jwtTokenProvider).parseAccessToken(bearerToken);
		verifyNoInteractions(service);
	}

	@Test
	void getPostReturnsOkWithEveryResponseField() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 26, 10, 30);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPost(73L))
			.thenReturn(new CommunityPostResponse(
				73L, "detail-author", "detail title", "detail content", createdAt, updatedAt, 9L, "BTC", "비트코인",
				null, null));

		mockMvc.perform(get("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(73))
			.andExpect(jsonPath("$.authorNickname").value("detail-author"))
			.andExpect(jsonPath("$.title").value("detail title"))
			.andExpect(jsonPath("$.content").value("detail content"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-26T10:30:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-27T12:00:00"))
			.andExpect(jsonPath("$.instrumentId").value(9))
			.andExpect(jsonPath("$.instrumentSymbol").value("BTC"))
			.andExpect(jsonPath("$.instrumentName").value("비트코인"));

		verify(service).getPost(73L);
	}

	@Test
	void getPostReturnsCommonNotFoundErrorWhenServiceCannotFindPost() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPost(404L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/community/posts/404")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).getPost(404L);
	}

	@Test
	void getPostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/community/posts/73"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void updatePostPreservesInstrumentTagAndPassesKeyAbsentToServiceWhenInstrumentIdOmitted() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 26, 10, 30);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 73L, "new title", "new content", false, null))
			.thenReturn(new CommunityPostResponse(
				73L, "author", "new title", "new content", createdAt, updatedAt, null, null, null, null, null));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"new title","content":"new content"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(73))
			.andExpect(jsonPath("$.authorNickname").value("author"))
			.andExpect(jsonPath("$.title").value("new title"))
			.andExpect(jsonPath("$.content").value("new content"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-26T10:30:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-27T12:00:00"))
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist())
			.andExpect(jsonPath("$.instrumentName").doesNotExist());

		verify(service).updatePost(USER_ID, 73L, "new title", "new content", false, null);
	}

	@Test
	void updatePostDetachesInstrumentTagAndPassesExplicitNullToServiceWhenInstrumentIdIsNull() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 26, 10, 30);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 73L, "new title", "new content", true, null))
			.thenReturn(new CommunityPostResponse(
				73L, "author", "new title", "new content", createdAt, updatedAt, null, null, null, null, null));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"new title","content":"new content","instrumentId":null}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist())
			.andExpect(jsonPath("$.instrumentName").doesNotExist());

		verify(service).updatePost(USER_ID, 73L, "new title", "new content", true, null);
	}

	@Test
	void updatePostPassesInstrumentIdToServiceAndReturnsTagFieldsWhenProvided() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 26, 10, 30);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 27, 12, 0);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 73L, "new title", "new content", true, 9L))
			.thenReturn(new CommunityPostResponse(
				73L, "author", "new title", "new content", createdAt, updatedAt, 9L, "BTC", "비트코인", null, null));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"new title","content":"new content","instrumentId":9}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").value(9))
			.andExpect(jsonPath("$.instrumentSymbol").value("BTC"))
			.andExpect(jsonPath("$.instrumentName").value("비트코인"));

		verify(service).updatePost(USER_ID, 73L, "new title", "new content", true, 9L);
	}

	@Test
	void updatePostReturnsCommonValidationErrorWhenServiceRejectsInstrumentTag() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 73L, "new title", "new content", true, 999L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"new title","content":"new content","instrumentId":999}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("존재하지 않거나 비활성인 종목은 태그할 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).updatePost(USER_ID, 73L, "new title", "new content", true, 999L);
	}

	@Test
	void updatePostRejectsNonNumericInstrumentIdWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"new title","content":"new content","instrumentId":"abc"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void updatePostRejectsInvalidTextWithoutCallingService(String scenario, String json) throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void getPostsReturnsOkWithDefaultPageAndSizeWhenParamsOmitted() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		CommunityPostResponse item = new CommunityPostResponse(
			7L, "author", "title", "content", now, now, null, null, null, null, null);
		when(service.getPosts(0, 10, null))
			.thenReturn(new CommunityPostListResponse(List.of(item), 0, 10, 1, 1, false));

		mockMvc.perform(get("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].postId").value(7))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(10))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.hasNext").value(false));

		verify(service).getPosts(0, 10, null);
	}

	@Test
	void getPostsPassesExplicitPageAndSizeToService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPosts(2, 5, null))
			.thenReturn(new CommunityPostListResponse(List.of(), 2, 5, 0, 0, false));

		mockMvc.perform(get("/api/community/posts")
			.param("page", "2")
			.param("size", "5")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());

		verify(service).getPosts(2, 5, null);
	}

	@Test
	void getPostsPassesInstrumentIdQueryParameterToService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPosts(0, 10, 9L))
			.thenReturn(new CommunityPostListResponse(List.of(), 0, 10, 0, 0, false));

		mockMvc.perform(get("/api/community/posts")
			.param("instrumentId", "9")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(service).getPosts(0, 10, 9L);
	}

	@Test
	void getPostsPassesNullInstrumentIdToServiceWhenParameterOmitted() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPosts(0, 10, null))
			.thenReturn(new CommunityPostListResponse(List.of(), 0, 10, 0, 0, false));

		mockMvc.perform(get("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(service).getPosts(0, 10, null);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidPageOrSize")
	void getPostsRejectsInvalidPageOrSizeWithoutCallingService(String scenario, String page, String size)
		throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(get("/api/community/posts")
			.param("page", page)
			.param("size", size)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void updatePostReturnsCommonNotFoundErrorWhenServiceCannotFindPost() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 404L, "new title", "new content", false, null))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/community/posts/404")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"new title\",\"content\":\"new content\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).updatePost(USER_ID, 404L, "new title", "new content", false, null);
	}

	@Test
	void updatePostReturnsCommonForbiddenErrorWhenServiceRejectsNonOwner() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.updatePost(USER_ID, 73L, "new title", "new content", false, null))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(patch("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"new title\",\"content\":\"new content\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("접근 권한이 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).updatePost(USER_ID, 73L, "new title", "new content", false, null);
	}

	@Test
	void updatePostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/community/posts/73")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"new title\",\"content\":\"new content\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void getPostsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/community/posts"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void deletePostReturnsNoContentWithEmptyBodyWhenOwnerDeletes() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(delete("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(service).deletePost(USER_ID, 73L);
	}

	@Test
	void deletePostReturnsCommonNotFoundErrorWhenServiceCannotFindPost() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(service).deletePost(USER_ID, 404L);

		mockMvc.perform(delete("/api/community/posts/404")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).deletePost(USER_ID, 404L);
	}

	@Test
	void deletePostReturnsCommonForbiddenErrorWhenServiceRejectsNonOwner() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(service).deletePost(USER_ID, 73L);

		mockMvc.perform(delete("/api/community/posts/73")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.message").value("접근 권한이 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).deletePost(USER_ID, 73L);
	}

	@Test
	void deletePostRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(delete("/api/community/posts/73"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	private static Stream<Arguments> invalidPageOrSize() {
		return Stream.of(
			Arguments.of("negative page", "-1", "10"),
			Arguments.of("size below minimum", "0", "0"),
			Arguments.of("size above maximum", "0", "51"));
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of("title null", "{\"title\":null,\"content\":\"content\"}"),
			Arguments.of("title blank", "{\"title\":\"   \",\"content\":\"content\"}"),
			Arguments.of("title overlength", request("t".repeat(101), "content")),
			Arguments.of("content null", "{\"title\":\"title\",\"content\":null}"),
			Arguments.of("content blank", "{\"title\":\"title\",\"content\":\"   \"}"),
			Arguments.of("content overlength", request("title", "c".repeat(5001))));
	}

	private static Stream<Arguments> rejectedBearerTokens() {
		return Stream.of(
			Arguments.of("expired access token", "expired.access.token"),
			Arguments.of("tampered access token", "tampered.access.token"),
			Arguments.of("refresh token", "refresh.jwt.token"));
	}

	private static String request(String title, String content) {
		return "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
	}
}
