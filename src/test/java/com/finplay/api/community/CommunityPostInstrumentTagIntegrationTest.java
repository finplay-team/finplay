// 실제 인증 필터와 MySQL을 연결해 게시글 종목 태그(COM-004) 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class CommunityPostInstrumentTagIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

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
		jdbcTemplate.update("delete from instruments where symbol like 'SYM%'");
	}

	// 이 클래스가 생성한 종목(symbol 접두사 SYM)만 정리한다 — 시드 데이터는 건드리지 않는다.
	// @BeforeEach는 다음 테스트 실행 전에만 청소하므로, 스위트의 마지막 테스트 뒤에도 정리되도록 @AfterEach를 둔다
	// (InstrumentRepositoryTest가 공유 Testcontainers에서 정확한 종목 개수를 기대하기 때문).
	@AfterEach
	void removeInstrumentsCreatedByThisTestClass() {
		jdbcTemplate.update("delete from community_posts");
		jdbcTemplate.update("delete from instruments where symbol like 'SYM%'");
	}

	@Test
	void createWithInstrumentTagIncludesTagFieldsOnDetailLookup() throws Exception {
		User author = createUser("tag-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		Instrument instrument = createTradableInstrument("삼성전자");

		String createResponseBody = mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"tagged title","content":"tagged content","instrumentId":%d}
				""".formatted(instrument.getId())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.instrumentId").value(instrument.getId()))
			.andExpect(jsonPath("$.instrumentSymbol").value(instrument.getSymbol()))
			.andExpect(jsonPath("$.instrumentName").value(instrument.getName()))
			.andReturn().getResponse().getContentAsString();
		Long postId = objectMapper.readTree(createResponseBody).get("postId").asLong();

		mockMvc.perform(get("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").value(instrument.getId()))
			.andExpect(jsonPath("$.instrumentSymbol").value(instrument.getSymbol()))
			.andExpect(jsonPath("$.instrumentName").value(instrument.getName()));
	}

	@Test
	void listFilteredByInstrumentIdExcludesOtherInstrumentAndUntaggedPosts() throws Exception {
		User author = createUser("filter-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		Instrument target = createTradableInstrument("삼성전자");
		Instrument other = createTradableInstrument("SK하이닉스");
		LocalDateTime base = LocalDateTime.now();

		Long taggedPostId = postRepository.saveAndFlush(
			CommunityPost.create(author, "tagged post", "content", target, base.minusMinutes(2))).getId();
		postRepository.saveAndFlush(
			CommunityPost.create(author, "other instrument post", "content", other, base.minusMinutes(1)));
		postRepository.saveAndFlush(
			CommunityPost.create(author, "untagged post", "content", null, base));

		String body = mockMvc.perform(get("/api/community/posts")
			.param("instrumentId", String.valueOf(target.getId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andReturn().getResponse().getContentAsString();

		JsonNode firstPost = objectMapper.readTree(body).get("content").get(0);
		assertThat(firstPost.get("postId").asLong()).isEqualTo(taggedPostId);
		assertThat(firstPost.get("instrumentId").asLong()).isEqualTo(target.getId());
	}

	@Test
	void createWithNonExistentInstrumentIdReturnsBadRequest() throws Exception {
		User author = createUser("no-instrument");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		long before = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"bad tag","content":"content","instrumentId":%d}
				""".formatted(Long.MAX_VALUE)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(postRepository.count()).isEqualTo(before);
	}

	@Test
	void createWithInactiveInstrumentIdReturnsBadRequest() throws Exception {
		User author = createUser("inactive-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		Instrument inactive = createInstrument("비활성종목", false);
		long before = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"bad tag","content":"content","instrumentId":%d}
				""".formatted(inactive.getId())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(postRepository.count()).isEqualTo(before);
	}

	@Test
	void untaggedPostRemainsBackwardCompatibleWithNullInstrumentFields() throws Exception {
		User author = createUser("untagged-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String createResponseBody = mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"untagged title\",\"content\":\"untagged content\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist())
			.andExpect(jsonPath("$.instrumentName").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		Long postId = objectMapper.readTree(createResponseBody).get("postId").asLong();

		mockMvc.perform(get("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("untagged title"))
			.andExpect(jsonPath("$.content").value("untagged content"))
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist())
			.andExpect(jsonPath("$.instrumentName").doesNotExist());
	}

	private Instrument createTradableInstrument(String name) {
		return createInstrument(name, true);
	}

	private Instrument createInstrument(String name, boolean tradable) {
		String symbol = "SYM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, name, BigDecimal.valueOf(100), 10000L, tradable,
				LocalDateTime.now()));
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
