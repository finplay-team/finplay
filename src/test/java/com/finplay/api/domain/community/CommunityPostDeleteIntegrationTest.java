// 실제 인증 필터와 MySQL을 연결해 게시글 삭제(댓글 포함) 핵심 시나리오를 검증하는 통합 테스트다.
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommunityPostDeleteIntegrationTest {

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

	@Test
	void ownerDeleteWithoutCommentsReturns204AndRemovesPostFromDatabase() throws Exception {
		User author = createUser("delete-owner-nocomment");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		Long postId = post.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(postRepository.findById(postId)).isEmpty();
	}

	@Test
	void ownerDeleteWithCommentsReturns204AndRemovesPostAndCommentsWithoutForeignKeyViolation() throws Exception {
		User author = createUser("delete-owner-withcomment");
		User commenter = createUser("delete-commenter");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		PostComment comment = commentRepository.saveAndFlush(
			PostComment.create(post, commenter, "comment", null, LocalDateTime.now()));
		Long postId = post.getId();
		Long commentId = comment.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(postRepository.findById(postId)).isEmpty();
		assertThat(commentRepository.findById(commentId)).isEmpty();
	}

	@Test
	void ownerDeleteWithParentCommentAndReplyReturns204AndRemovesPostParentAndChildWithoutStaleStateException()
		throws Exception {
		User author = createUser("delete-owner-withreply");
		User commenter = createUser("delete-reply-commenter");
		User replier = createUser("delete-reply-replier");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		PostComment parent = commentRepository.saveAndFlush(
			PostComment.create(post, commenter, "parent comment", null, LocalDateTime.now()));
		PostComment reply = commentRepository.saveAndFlush(
			PostComment.create(post, replier, "reply comment", parent, LocalDateTime.now()));
		Long postId = post.getId();
		Long parentId = parent.getId();
		Long replyId = reply.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(postRepository.findById(postId)).isEmpty();
		assertThat(commentRepository.findById(parentId)).isEmpty();
		assertThat(commentRepository.findById(replyId)).isEmpty();
	}

	@Test
	void nonOwnerDeleteReturnsForbiddenAndLeavesPostAndCommentsInDatabase() throws Exception {
		User author = createUser("delete-forbidden-owner");
		User stranger = createUser("delete-forbidden-stranger");
		User commenter = createUser("delete-forbidden-commenter");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		PostComment comment = commentRepository.saveAndFlush(
			PostComment.create(post, commenter, "comment", null, LocalDateTime.now()));
		Long postId = post.getId();
		Long commentId = comment.getId();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		assertThat(postRepository.findById(postId)).isPresent();
		assertThat(commentRepository.findById(commentId)).isPresent();
	}

	@Test
	void deleteOnMissingPostReturnsNotFound() throws Exception {
		User user = createUser("delete-missing-user");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void unauthenticatedDeleteReturnsUnauthorized() throws Exception {
		User author = createUser("delete-unauth-owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		Long postId = post.getId();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		assertThat(postRepository.findById(postId)).isPresent();
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
