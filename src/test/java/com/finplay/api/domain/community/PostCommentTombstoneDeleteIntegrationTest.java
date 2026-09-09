package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PostCommentTombstoneDeleteIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 12, 0);

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
	void deletingParentWithReplyTombstonesParentDisplayAndKeepsReplyContentUnchanged() throws Exception {
		User author = createUser("tomb-parent-rep");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent original content", null, NOW));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, author, "reply original content", parent, NOW.plusMinutes(1)));
		Long parentId = parent.getId();
		Long replyId = reply.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", parentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].commentId").value(parentId))
			.andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다"))
			.andExpect(jsonPath("$[0].authorNickname").value("(삭제됨)"))
			.andExpect(jsonPath("$[0].replies.length()").value(1))
			.andExpect(jsonPath("$[0].replies[0].commentId").value(replyId))
			.andExpect(jsonPath("$[0].replies[0].content").value("reply original content"))
			.andExpect(jsonPath("$[0].replies[0].authorNickname").value(author.getNickname()));

		assertThat(commentRepository.findById(parentId)).isPresent();
		assertThat(commentRepository.findById(parentId).orElseThrow().isTombstoned()).isTrue();
	}

	@Test
	void deletingParentWithNoReplyStillTombstonesInsteadOfHardDeleting() throws Exception {
		User author = createUser("tomb-parent-no");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "lonely parent", null, NOW));
		Long parentId = parent.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", parentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].commentId").value(parentId))
			.andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다"))
			.andExpect(jsonPath("$[0].authorNickname").value("(삭제됨)"))
			.andExpect(jsonPath("$[0].replies").isEmpty());

		assertThat(commentRepository.findById(parentId)).isPresent();
		assertThat(commentRepository.findById(parentId).orElseThrow().isTombstoned()).isTrue();
	}

	@Test
	void deletingReplyItselfStillHardDeletesAndRemovesItFromParentReplies() throws Exception {
		User author = createUser("tomb-reply-self");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent", null, NOW));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		Long parentId = parent.getId();
		Long replyId = reply.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", replyId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(commentRepository.findById(replyId)).isEmpty();

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].commentId").value(parentId))
			.andExpect(jsonPath("$[0].content").value("parent"))
			.andExpect(jsonPath("$[0].replies").isEmpty());
	}

	@Test
	void strangerDeleteAttemptOnParentOrReplyStaysForbiddenAfterTombstoneIntroduction() throws Exception {
		User author = createUser("tomb-owner");
		User stranger = createUser("tomb-stranger");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent", null, NOW));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		Long parentId = parent.getId();
		Long replyId = reply.getId();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", parentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(delete("/api/community/comments/{commentId}", replyId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(commentRepository.findById(parentId).orElseThrow().isTombstoned()).isFalse();
		assertThat(commentRepository.findById(replyId)).isPresent();
	}

	@Test
	void replyingToTombstonedParentReturns400AndDoesNotAppearOnReQuery() throws Exception {
		User author = createUser("tomb-reply-block");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "post", null, NOW));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, author, "parent original content", null, NOW));
		Long parentId = parent.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/comments/{commentId}", parentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"late reply\",\"parentCommentId\":" + parentId + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("삭제된 댓글에는 답글을 남길 수 없습니다."));

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].commentId").value(parentId))
			.andExpect(jsonPath("$[0].content").value("삭제된 댓글입니다"))
			.andExpect(jsonPath("$[0].replies").isEmpty());
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
