// 게시글 생성 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
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
		when(service.createPost(USER_ID, "title", "content"))
			.thenReturn(new CommunityPostResponse(7L, "author", "title", "content", now, now));

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
			.andExpect(jsonPath("$.updatedAt").value("2026-07-27T12:00:00"));

		verify(service).createPost(USER_ID, "title", "content");
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
	void getPostsReturnsOkWithDefaultPageAndSizeWhenParamsOmitted() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime now = LocalDateTime.of(2026, 7, 27, 12, 0);
		CommunityPostResponse item = new CommunityPostResponse(7L, "author", "title", "content", now, now);
		when(service.getPosts(0, 10))
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

		verify(service).getPosts(0, 10);
	}

	@Test
	void getPostsPassesExplicitPageAndSizeToService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.getPosts(2, 5))
			.thenReturn(new CommunityPostListResponse(List.of(), 2, 5, 0, 0, false));

		mockMvc.perform(get("/api/community/posts")
			.param("page", "2")
			.param("size", "5")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());

		verify(service).getPosts(2, 5);
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
	void getPostsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/community/posts"))
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
