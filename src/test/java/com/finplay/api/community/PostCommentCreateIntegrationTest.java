// 실제 인증 필터와 MySQL을 연결해 댓글 생성 핵심 시나리오와 실패 시 DB 불변을 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PostCommentCreateIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private PostCommentRepository commentRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabaseInForeignKeySafeOrder() {
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void authenticatedCreateReturns201AndPersistsPostAndTokenUserAssociations() throws Exception {
		User postAuthor = createUser("poster");
		User commentAuthor = createUser("commenter");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(postAuthor, "title", "post", LocalDateTime.now()));
		String accessToken = jwtTokenProvider.issue(commentAuthor.getId(), commentAuthor.getRole()).accessToken();

		String response = mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"integration comment\",\"authorId\":999,\"parentCommentId\":999}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.commentId").isNumber())
			.andExpect(jsonPath("$.authorNickname").value(commentAuthor.getNickname()))
			.andExpect(jsonPath("$.content").value("integration comment"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.postId").doesNotExist())
			.andExpect(jsonPath("$.authorId").doesNotExist())
			.andExpect(jsonPath("$.parentCommentId").doesNotExist())
			.andReturn().getResponse().getContentAsString();

		assertThat(response).contains("\"commentId\"");
		assertThat(commentRepository.findAll()).singleElement().satisfies(comment -> {
			assertThat(comment.getPost().getId()).isEqualTo(post.getId());
			assertThat(comment.getAuthor().getId()).isEqualTo(commentAuthor.getId());
			assertThat(comment.getContent()).isEqualTo("integration comment");
			assertThat(comment.getCreatedAt()).isNotNull();
		});
	}

	@Test
	void invalidMissingPostAndUnauthenticatedRequestsLeaveCommentsUnchanged() throws Exception {
		User author = createUser("failure");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", LocalDateTime.now()));
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		assertThat(commentRepository.count()).isZero();

		mockMvc.perform(post("/api/community/posts/{postId}/comments", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		assertThat(commentRepository.count()).isZero();

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"comment\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		assertThat(commentRepository.count()).isZero();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			prefix + "-" + unique,
			LocalDateTime.now()));
	}
}
