// 실제 인증 필터와 MySQL을 연결해 댓글 목록의 격리, 정렬, 오류 계약과 조회 불변성을 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PostCommentListIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 0);

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
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 단일 "delete from post_comments"는
		// 다른 테스트 컨텍스트가 남긴 부모+자식이 섞여 있으면 행 처리 순서 미보장으로 실패할 수 있다(이슈 #277).
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void authenticatedListReturnsOnlyTargetCommentsInCreatedAtAndIdOrderWithoutChangingDatabase()
		throws Exception {
		User requester = createUser("requester");
		User secondAuthor = createUser("second");
		CommunityPost target = postRepository
			.saveAndFlush(CommunityPost.create(requester, "target", "post", null, NOW));
		CommunityPost other = postRepository.saveAndFlush(CommunityPost.create(requester, "other", "post", null, NOW));
		PostComment oldest = commentRepository.saveAndFlush(
			PostComment.create(target, requester, "oldest", null, NOW.minusMinutes(1)));
		PostComment firstTie = commentRepository.saveAndFlush(
			PostComment.create(target, requester, "first tie", null, NOW));
		PostComment secondTie = commentRepository.saveAndFlush(
			PostComment.create(target, secondAuthor, "second tie", null, NOW));
		commentRepository
			.saveAndFlush(PostComment.create(other, secondAuthor, "other post", null, NOW.minusMinutes(2)));
		long countBefore = commentRepository.count();
		String token = jwtTokenProvider.issue(requester.getId(), requester.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts/{postId}/comments", target.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].commentId").value(oldest.getId()))
			.andExpect(jsonPath("$[0].authorNickname").value(requester.getNickname()))
			.andExpect(jsonPath("$[0].content").value("oldest"))
			.andExpect(jsonPath("$[0].createdAt").value("2026-07-27T11:59:00"))
			.andExpect(jsonPath("$[0].postId").doesNotExist())
			.andExpect(jsonPath("$[0].authorId").doesNotExist())
			.andExpect(jsonPath("$[1].commentId").value(firstTie.getId()))
			.andExpect(jsonPath("$[2].commentId").value(secondTie.getId()))
			.andExpect(jsonPath("$[2].authorNickname").value(secondAuthor.getNickname()))
			.andExpect(jsonPath("$[2].content").value("second tie"));

		assertThat(commentRepository.count()).isEqualTo(countBefore);
	}

	@Test
	void existingPostWithoutCommentsReturnsEmptyArrayWithoutChangingDatabase() throws Exception {
		User requester = createUser("empty");
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(requester, "empty", "post", null, NOW));
		String token = jwtTokenProvider.issue(requester.getId(), requester.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts/{postId}/comments", post.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());

		assertThat(commentRepository.count()).isZero();
	}

	@Test
	void missingPostAndUnauthenticatedRequestsReturnErrorsWithoutChangingDatabase() throws Exception {
		User requester = createUser("errors");
		String token = jwtTokenProvider.issue(requester.getId(), requester.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts/{postId}/comments", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
		assertThat(commentRepository.count()).isZero();

		mockMvc.perform(get("/api/community/posts/{postId}/comments", Long.MAX_VALUE))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		assertThat(commentRepository.count()).isZero();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(prefix + "-" + unique + "@finplay.com", "hash", prefix + "-" + unique, NOW));
	}
}
