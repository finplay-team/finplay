// 실제 인증 필터와 MySQL을 연결해 대댓글 중첩 조회, 2단계 제한, 소유권 삭제, CASCADE 삭제를 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.PostComment;
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
class PostCommentReplyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 12, 0);

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
	void createReplyThenListReturnsParentWithChildOrderedByOldestFirst() throws Exception {
		User author = createUser("reply-author");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String parentResponse = mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"parent comment\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentCommentId").doesNotExist())
			.andExpect(jsonPath("$.replies").isArray())
			.andExpect(jsonPath("$.replies").isEmpty())
			.andReturn().getResponse().getContentAsString();
		Long parentId = Long.valueOf(parentResponse.replaceAll(".*\"commentId\":(\\d+).*", "$1"));

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"first reply\",\"parentCommentId\":" + parentId + "}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentCommentId").value(parentId))
			.andExpect(jsonPath("$.replies").isEmpty());

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"second reply\",\"parentCommentId\":" + parentId + "}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentCommentId").value(parentId));

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].commentId").value(parentId))
			.andExpect(jsonPath("$[0].content").value("parent comment"))
			.andExpect(jsonPath("$[0].replies.length()").value(2))
			.andExpect(jsonPath("$[0].replies[0].content").value("first reply"))
			.andExpect(jsonPath("$[0].replies[1].content").value("second reply"))
			.andExpect(jsonPath("$[0].replies[0].parentCommentId").value(parentId))
			.andExpect(jsonPath("$[0].replies[1].parentCommentId").value(parentId));

		assertThat(commentRepository.count()).isEqualTo(3);
	}

	@Test
	void replyingToAReplyReturnsBadRequestAndLeavesCommentsUnchanged() throws Exception {
		User author = createUser("nested-author");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent", null, NOW));
		PostComment child = commentRepository.saveAndFlush(
			PostComment.create(post, author, "child", parent, NOW.plusMinutes(1)));
		long countBefore = commentRepository.count();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"reply to reply\",\"parentCommentId\":" + child.getId() + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(commentRepository.count()).isEqualTo(countBefore);
	}

	@Test
	void ownerDeletesOwnReplyButStrangerDeleteAttemptIsForbidden() throws Exception {
		User author = createUser("owner-author");
		User stranger = createUser("stranger");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent", null, NOW));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, author, "my reply", parent, NOW.plusMinutes(1)));
		Long replyId = reply.getId();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();
		String ownerToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", replyId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		assertThat(commentRepository.findById(replyId)).isPresent();

		mockMvc.perform(delete("/api/community/comments/{commentId}", replyId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
			.andExpect(status().isNoContent());
		assertThat(commentRepository.findById(replyId)).isEmpty();
	}

	@Test
	void deletingParentCommentCascadesToChildReplies() throws Exception {
		User author = createUser("cascade-author");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent", null, NOW));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		Long parentId = parent.getId();
		Long replyId = reply.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", parentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(commentRepository.findById(parentId)).isEmpty();
		assertThat(commentRepository.findById(replyId)).isEmpty();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			prefix + "-" + unique,
			NOW));
	}
}
