// 실제 인증 필터와 MySQL을 연결해 게시글 수정 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class CommunityPostUpdateIntegrationTest {

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
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 이 클래스가 생성한 종목(symbol 접두사 SYM)만 정리한다 — InstrumentRepositoryTest의 개수 단정을 깨지 않기 위해
	// (CommunityPostInstrumentTagIntegrationTest와 동일한 패턴).
	@AfterEach
	void removeInstrumentsCreatedByThisTestClass() {
		jdbcTemplate.update("delete from community_posts where instrument_id in "
			+ "(select id from instruments where symbol like 'SYM%')");
		jdbcTemplate.update("delete from instruments where symbol like 'SYM%'");
	}

	@Test
	void ownerPatchUpdatesTitleContentAndUpdatedAtInDatabase() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("update-owner");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", null, createdAt));
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
	void ownerPatchPreservesInstrumentTagWhenInstrumentIdKeyIsOmitted() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("preserve-owner");
		Instrument instrument = createTradableInstrument();
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", instrument, createdAt));
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
			.andExpect(jsonPath("$.instrumentId").value(instrument.getId()))
			.andExpect(jsonPath("$.instrumentSymbol").value(instrument.getSymbol()));

		entityManager.clear();
		CommunityPost reloaded = postRepository.findById(postId).orElseThrow();
		assertThat(reloaded.getInstrument()).isNotNull();
		assertThat(reloaded.getInstrument().getId()).isEqualTo(instrument.getId());
	}

	@Test
	void ownerPatchDetachesInstrumentTagWhenInstrumentIdIsExplicitlyNull() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("detach-owner");
		Instrument instrument = createTradableInstrument();
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", instrument, createdAt));
		Long postId = post.getId();
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		entityManager.clear();

		mockMvc.perform(patch("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"updated title","content":"updated content","instrumentId":null}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").doesNotExist())
			.andExpect(jsonPath("$.instrumentSymbol").doesNotExist());

		entityManager.clear();
		CommunityPost reloaded = postRepository.findById(postId).orElseThrow();
		assertThat(reloaded.getInstrument()).isNull();
	}

	@Test
	void nonOwnerPatchReturnsForbiddenAndLeavesDatabaseUnchanged() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		User author = createUser("forbidden-owner");
		User stranger = createUser("forbidden-stranger");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "original title", "original content", null, createdAt));
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
			CommunityPost.create(author, "original title", "original content", null, createdAt));
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
			CommunityPost.create(author, "original title", "original content", null, createdAt));
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

	private Instrument createTradableInstrument() {
		String symbol = "SYM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, "테스트종목", BigDecimal.valueOf(100), 10000L, true,
				LocalDateTime.now()));
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
