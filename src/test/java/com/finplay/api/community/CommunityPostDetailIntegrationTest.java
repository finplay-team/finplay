// 실제 인증 필터와 MySQL을 연결해 게시글 단건 조회 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.repository.CommunityPostRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@Rollback
class CommunityPostDetailIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private EntityManager entityManager;

	@Test
	void authenticatedGetReturnsPersistedPostAndAuthorAfterPersistenceContextClear() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 26, 10, 30, 15);
		User author = createUser("detail-author");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "detail title", "detail content", createdAt));
		Long postId = post.getId();
		String authorNickname = author.getNickname();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		entityManager.clear();

		mockMvc.perform(get("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(postId))
			.andExpect(jsonPath("$.authorNickname").value(authorNickname))
			.andExpect(jsonPath("$.title").value("detail title"))
			.andExpect(jsonPath("$.content").value("detail content"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-26T10:30:15"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-26T10:30:15"));
	}

	@Test
	void authenticatedGetReturnsNotFoundForMissingPost() throws Exception {
		User user = createUser("missing-reader");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts/{postId}", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void unauthenticatedGetReturnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/community/posts/{postId}", Long.MAX_VALUE))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
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
