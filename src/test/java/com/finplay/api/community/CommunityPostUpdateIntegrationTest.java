// 실제 인증 필터와 MySQL을 연결해 게시글 수정 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommunityPostUpdateIntegrationTest {

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
	void ownerPatchUpdatesTitleContentAndUpdatedAtInDatabase() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("update-owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", createdAt));
		Long postId = post.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		entityManager.clear();

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"updated title","content":"updated content"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(postId))
			.andExpect(jsonPath("$.title").value("updated title"))
			.andExpect(jsonPath("$.content").value("updated content"));

		entityManager.clear();
		CommunityPost reloaded = postRepository.findById(postId).orElseThrow();
		assertThat(reloaded.getTitle()).isEqualTo("updated title");
		assertThat(reloaded.getContent()).isEqualTo("updated content");
		assertThat(reloaded.getUpdatedAt()).isAfter(createdAt);
		assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
	}

	@Test
	void nonOwnerPatchReturnsForbiddenAndLeavesDatabaseUnchanged() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("forbidden-owner");
		User stranger = createUser("forbidden-stranger");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", createdAt));
		Long postId = post.getId();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();
		entityManager.clear();

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"hacked title","content":"hacked content"}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		entityManager.clear();
		CommunityPost reloaded = postRepository.findById(postId).orElseThrow();
		assertThat(reloaded.getTitle()).isEqualTo("original title");
		assertThat(reloaded.getContent()).isEqualTo("original content");
		assertThat(reloaded.getUpdatedAt()).isEqualTo(createdAt);
	}

	@Test
	void patchOnMissingPostReturnsNotFound() throws Exception {
		User user = createUser("missing-patcher");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(patch("/api/community/posts/{postId}", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"any title","content":"any content"}
				"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void blankTitleOrContentReturnsValidationErrorAndLeavesDatabaseUnchanged() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("blank-owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", createdAt));
		Long postId = post.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		entityManager.clear();

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\" \",\"content\":\"content\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"title\",\"content\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		entityManager.clear();
		CommunityPost reloaded = postRepository.findById(postId).orElseThrow();
		assertThat(reloaded.getTitle()).isEqualTo("original title");
		assertThat(reloaded.getContent()).isEqualTo("original content");
		assertThat(reloaded.getUpdatedAt()).isEqualTo(createdAt);
	}

	@Test
	void unauthenticatedPatchReturnsUnauthorized() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("unauth-owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", createdAt));
		Long postId = post.getId();

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"any title","content":"any content"}
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
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
