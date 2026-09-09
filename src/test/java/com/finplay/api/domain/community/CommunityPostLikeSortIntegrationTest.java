package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class CommunityPostLikeSortIntegrationTest {

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
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_post_likes");
		jdbcTemplate.update("delete from community_posts");
	}

	@AfterEach
	void removeInstrumentsCreatedByThisTestClass() {
		jdbcTemplate.update("delete from community_post_likes");
		jdbcTemplate.update("delete from community_posts");
		jdbcTemplate.update("delete from instruments where symbol like 'SYM%'");
	}

	@Test
	void likingAnotherMembersPostIncreasesLikeCountAndSetsLikedByMeTrue() throws Exception {
		User author = createUser("like-author");
		User liker = createUser("like-liker");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(targetPost.getId()))
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));

		mockMvc.perform(get("/api/community/posts/{postId}", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));
	}

	@Test
	void likingSamePostAgainIsIdempotentAndReturnsUnchangedStateWithOk() throws Exception {
		User author = createUser("idem-author");
		User liker = createUser("idem-liker");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));

		assertThat(countLikes(targetPost.getId())).isEqualTo(1);
	}

	@Test
	void unlikingDecreasesLikeCountAndSetsLikedByMeFalse() throws Exception {
		User author = createUser("unlike-author");
		User liker = createUser("unlike-liker");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();
		likePost(targetPost.getId(), likerToken);

		mockMvc.perform(delete("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/community/posts/{postId}", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + likerToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.likeCount").value(0))
			.andExpect(jsonPath("$.likedByMe").value(false));
	}

	@Test
	void unlikingWhenNeverLikedReturnsNoContentWithoutError() throws Exception {
		User author = createUser("noop-author");
		User stranger = createUser("noop-stranger");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isNoContent());

		assertThat(countLikes(targetPost.getId())).isEqualTo(0);
	}

	@Test
	void likingOwnPostIsAllowedAndNotBlocked() throws Exception {
		User author = createUser("self-author");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		String authorToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/likes", targetPost.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));
	}

	@Test
	void likingNonExistentPostReturnsNotFound() throws Exception {
		User user = createUser("missing-like-user");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(post("/api/community/posts/{postId}/likes", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void unlikingNonExistentPostReturnsNotFound() throws Exception {
		User user = createUser("missing-unlike-user");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}/likes", Long.MAX_VALUE)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void popularSortReturnsPostsOrderedByLikeCountDescending() throws Exception {
		User author = createUser("popular-author");
		User liker1 = createUser("popular-liker1");
		User liker2 = createUser("popular-liker2");
		LocalDateTime base = LocalDateTime.now();
		CommunityPost mostLiked = postRepository.saveAndFlush(
			CommunityPost.create(author, "most liked", "content", null, base.minusMinutes(2)));
		CommunityPost oneLike = postRepository.saveAndFlush(
			CommunityPost.create(author, "one like", "content", null, base.minusMinutes(1)));
		CommunityPost noLikes = postRepository.saveAndFlush(
			CommunityPost.create(author, "no likes", "content", null, base));
		String liker1Token = jwtTokenProvider.issue(liker1.getId(), liker1.getRole()).accessToken();
		String liker2Token = jwtTokenProvider.issue(liker2.getId(), liker2.getRole()).accessToken();
		likePost(mostLiked.getId(), liker1Token);
		likePost(mostLiked.getId(), liker2Token);
		likePost(oneLike.getId(), liker1Token);
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String body = mockMvc.perform(get("/api/community/posts")
			.param("sort", "popular")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(extractPostIds(body)).containsExactly(mostLiked.getId(), oneLike.getId(), noLikes.getId());
	}

	@Test
	void popularSortBreaksTiesByNewestFirstWhenLikeCountsAreEqual() throws Exception {
		User author = createUser("tie-author");
		LocalDateTime base = LocalDateTime.now();
		CommunityPost older = postRepository.saveAndFlush(
			CommunityPost.create(author, "older", "content", null, base.minusMinutes(1)));
		CommunityPost newer = postRepository.saveAndFlush(
			CommunityPost.create(author, "newer", "content", null, base));
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String body = mockMvc.perform(get("/api/community/posts")
			.param("sort", "popular")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(extractPostIds(body)).containsExactly(newer.getId(), older.getId());
	}

	@Test
	void omittingSortParameterReturnsBackwardCompatibleNewestFirstOrder() throws Exception {
		User author = createUser("compat-author");
		User liker = createUser("compat-liker");
		LocalDateTime base = LocalDateTime.now();
		CommunityPost older = postRepository.saveAndFlush(
			CommunityPost.create(author, "older", "content", null, base.minusMinutes(1)));
		CommunityPost newer = postRepository.saveAndFlush(
			CommunityPost.create(author, "newer", "content", null, base));
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();
		likePost(older.getId(), likerToken);
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String body = mockMvc.perform(get("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(extractPostIds(body)).containsExactly(newer.getId(), older.getId());
	}

	@Test
	void popularSortCombinedWithInstrumentIdFiltersAndOrdersWithinThatInstrument() throws Exception {
		User author = createUser("filter-sort-author");
		User liker = createUser("filter-sort-liker");
		Instrument target = createTradableInstrument("삼성전자");
		Instrument other = createTradableInstrument("SK하이닉스");
		LocalDateTime base = LocalDateTime.now();
		CommunityPost taggedLessLiked = postRepository.saveAndFlush(
			CommunityPost.create(author, "tagged less liked", "content", target, base.minusMinutes(2)));
		CommunityPost taggedMostLiked = postRepository.saveAndFlush(
			CommunityPost.create(author, "tagged most liked", "content", target, base.minusMinutes(1)));
		postRepository.saveAndFlush(
			CommunityPost.create(author, "other instrument, more likes", "content", other, base));
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();
		likePost(taggedMostLiked.getId(), likerToken);
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String body = mockMvc.perform(get("/api/community/posts")
			.param("sort", "popular")
			.param("instrumentId", String.valueOf(target.getId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andReturn().getResponse().getContentAsString();

		assertThat(extractPostIds(body)).containsExactly(taggedMostLiked.getId(), taggedLessLiked.getId());
	}

	@Test
	void unsupportedSortValueReturnsBadRequestWithoutQueryingPosts() throws Exception {
		User user = createUser("bad-sort-user");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(get("/api/community/posts")
			.param("sort", "trending")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void deletingPostRemovesItsLikesWithoutOrphanRows() throws Exception {
		User author = createUser("cleanup-author");
		User liker = createUser("cleanup-liker");
		CommunityPost targetPost = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		Long postId = targetPost.getId();
		String likerToken = jwtTokenProvider.issue(liker.getId(), liker.getRole()).accessToken();
		likePost(postId, likerToken);
		assertThat(countLikes(postId)).isEqualTo(1);
		String authorToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + authorToken))
			.andExpect(status().isNoContent());

		assertThat(countLikes(postId)).isEqualTo(0);
	}

	private void likePost(Long postId, String accessToken) throws Exception {
		mockMvc.perform(post("/api/community/posts/{postId}/likes", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isCreated());
	}

	private long countLikes(Long postId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from community_post_likes where post_id = ?", Long.class, postId);
		return count == null ? 0 : count;
	}

	private List<Long> extractPostIds(String responseBody) throws Exception {
		JsonNode content = objectMapper.readTree(responseBody).get("content");
		return content.findValuesAsText("postId").stream().map(Long::valueOf).toList();
	}

	private Instrument createTradableInstrument(String name) {
		String symbol = "SYM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, name, BigDecimal.valueOf(100), 10000L, true,
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
