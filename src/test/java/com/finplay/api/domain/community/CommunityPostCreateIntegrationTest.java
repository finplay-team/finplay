// 실제 인증 필터와 MySQL을 연결해 게시글 생성 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommunityPostCreateIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void authenticatedCreateReturns201AndPersistsTokenUserAsAuthor() throws Exception {
		User author = createUser("author");
		User forged = createUser("forged");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		long before = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"integration title","content":"integration content","authorId":%d,"userId":%d}
				""".formatted(forged.getId(), forged.getId())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").isNumber())
			.andExpect(jsonPath("$.authorNickname").value(author.getNickname()))
			.andExpect(jsonPath("$.title").value("integration title"))
			.andExpect(jsonPath("$.content").value("integration content"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		assertThat(postRepository.count()).isEqualTo(before + 1);
		assertThat(postRepository.findAll()).filteredOn(post -> post.getTitle().equals("integration title"))
			.singleElement()
			.satisfies(post -> assertThat(post.getAuthor().getId()).isEqualTo(author.getId()));
	}

	@Test
	void blankAndUnauthenticatedRequestsLeaveDatabaseUnchanged() throws Exception {
		User author = createUser("validation");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		long before = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\" \",\"content\":\"content\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(post("/api/community/posts")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"title\",\"content\":\"content\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		assertThat(postRepository.count()).isEqualTo(before);
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
