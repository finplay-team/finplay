// 실제 인증 필터와 MySQL을 연결해 부모 댓글 삭제 tombstone 전환(이슈 #277)의 핵심 시나리오를 검증하는 통합 테스트다.
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
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 자식(대댓글)을 먼저 지우지 않으면
		// 단일 DELETE 문 안에서 부모가 먼저 처리될 경우 제약 위반이 날 수 있다(이슈 #277).
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

	// 이슈 #277 / PR #331 리뷰 참고 사항 #2: 부모 댓글을 tombstone한 뒤 그 부모로 대댓글을 시도하면
	// 400이며, 재조회 응답에도 새 대댓글이 반영되지 않아야 한다(글타래가 계속 자라지 않는다).
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
