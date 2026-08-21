// 실제 인증 필터와 MySQL을 연결해 게시글 목록 조회 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
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
class CommunityPostListIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void removePostsPersistedByOtherIntegrationTests() {
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 단일 "delete from post_comments"는
		// 다른 테스트 컨텍스트가 남긴 부모+자식이 섞여 있으면 행 처리 순서 미보장으로 실패할 수 있다(이슈 #277).
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void authenticatedListReturnsPostsNewestFirstWithoutDuplicatesAcrossPages() throws Exception {
		User author = createUser("lister");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		LocalDateTime base = LocalDateTime.now();
		List<Long> createdIds = List.of(
			postRepository.saveAndFlush(CommunityPost.create(author, "p1", "content", null, base.minusMinutes(4)))
				.getId(),
			postRepository.saveAndFlush(CommunityPost.create(author, "p2", "content", null, base.minusMinutes(3)))
				.getId(),
			postRepository.saveAndFlush(CommunityPost.create(author, "p3", "content", null, base.minusMinutes(2)))
				.getId(),
			postRepository.saveAndFlush(CommunityPost.create(author, "p4", "content", null, base.minusMinutes(1)))
				.getId(),
			postRepository.saveAndFlush(CommunityPost.create(author, "p5", "content", null, base)).getId());

		String firstPageBody = mockMvc.perform(get("/api/community/posts")
			.param("page", "0")
			.param("size", "3")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(3))
			.andExpect(jsonPath("$.totalElements").value(5))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.hasNext").value(true))
			.andReturn().getResponse().getContentAsString();

		String secondPageBody = mockMvc.perform(get("/api/community/posts")
			.param("page", "1")
			.param("size", "3")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andReturn().getResponse().getContentAsString();

		List<Long> combinedIds = concatPostIds(firstPageBody, secondPageBody);
		assertThat(combinedIds).hasSize(5).doesNotHaveDuplicates()
			.containsExactlyInAnyOrderElementsOf(createdIds);
	}

	@Test
	void emptyListReturnsOkWithEmptyContent() throws Exception {
		User author = createUser("empty");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		mockMvc.perform(get("/api/community/posts"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	private List<Long> concatPostIds(String... responseBodies) {
		return List.of(responseBodies).stream()
			.flatMap(body -> {
				try {
					JsonNode content = objectMapper.readTree(body).get("content");
					return content.findValuesAsText("postId").stream().map(Long::valueOf);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			})
			.toList();
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
