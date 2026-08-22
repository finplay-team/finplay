// 실제 인증 필터와 MySQL을 연결해 대댓글 중첩 조회, 2단계 제한, 소유권 삭제(자식 하드 삭제)를 검증하는 통합 테스트다.
// 부모 댓글 삭제 시 tombstone 전환(이슈 #277) 시나리오는 PostCommentTombstoneDeleteIntegrationTest에서 다룬다.
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
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 자식(대댓글)을 먼저 지우지 않으면
		// 단일 DELETE 문 안에서 부모가 먼저 처리될 경우 제약 위반이 날 수 있다(이슈 #277).
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
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
	void deletingParentCommentTombstonesItInsteadOfRemovingItAndKeepsChildReplies() throws Exception {
		// 이슈 #277: V25의 ON DELETE CASCADE(부모 삭제 시 자식도 함께 삭제)는 tombstone 전환으로
		// 대체됐다 — 전체 시나리오(응답 content/authorNickname 치환 등)는
		// PostCommentTombstoneDeleteIntegrationTest에서 검증하고, 여기서는 자식이 더 이상 CASCADE로
		// 사라지지 않는다는 회귀만 확인한다.
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

		assertThat(commentRepository.findById(parentId)).isPresent();
		assertThat(commentRepository.findById(parentId).orElseThrow().isTombstoned()).isTrue();
		assertThat(commentRepository.findById(replyId)).isPresent();
		assertThat(commentRepository.findById(replyId).orElseThrow().isTombstoned()).isFalse();
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
