package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.PostComment;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.community.repository.PostCommentRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommentDeleteIntegrationTest {

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
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void ownerDeleteReturns204AndTombstonesTopLevelCommentInsteadOfRemovingIt() throws Exception {
		User author = createUser("owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		PostComment comment = commentRepository.saveAndFlush(
			PostComment.create(post, author, "my comment", null, LocalDateTime.now()));
		Long commentId = comment.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", commentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		PostComment tombstoned = commentRepository.findById(commentId).orElseThrow();
		assertThat(tombstoned.isTombstoned()).isTrue();
	}

	@Test
	void nonAuthorDeleteReturnsForbiddenAndLeavesCommentInDatabase() throws Exception {
		User author = createUser("forbidden-author");
		User stranger = createUser("forbidden-stranger");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		PostComment comment = commentRepository.saveAndFlush(
			PostComment.create(post, author, "original comment", null, LocalDateTime.now()));
		Long commentId = comment.getId();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", commentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		assertThat(commentRepository.findById(commentId)).isPresent();
	}

	@Test
	void deleteOnMissingCommentReturnsNotFound() throws Exception {
		User user = createUser("missing-deleter");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void unauthenticatedDeleteReturnsUnauthorizedAndLeavesCommentCountUnchanged() throws Exception {
		User author = createUser("unauth-author");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		commentRepository.saveAndFlush(
			PostComment.create(post, author, "untouched comment", null, LocalDateTime.now()));
		long countBefore = commentRepository.count();

		mockMvc.perform(delete("/api/community/comments/{commentId}", Long.MAX_VALUE))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		assertThat(commentRepository.count()).isEqualTo(countBefore);
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		String nickname = prefix + "-" + unique;
		if (nickname.length() > 50) {
			nickname = nickname.substring(0, 50);
		}
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			nickname,
			LocalDateTime.now()));
	}
}
