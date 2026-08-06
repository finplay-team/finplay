// 관심목록 등록→목록 조회→해제→목록 제외와 MySQL 영속화를 실제 인증·MockMvc로 검증하는 통합 테스트다.
package com.finplay.api.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.watchlist.domain.WatchlistItem;
import com.finplay.api.watchlist.repository.WatchlistItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WatchlistIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private WatchlistItemRepository watchlistItemRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void removeWatchlistItemsPersistedByOtherIntegrationTests() {
		jdbcTemplate.update("delete from watchlist_items");
	}

	@Test
	void registerListUnregisterAndConfirmExclusionFromList() throws Exception {
		User user = createUser("watcher");
		Instrument instrument = seedInstrument(Market.STOCK, "005930");
		Instrument otherInstrument = seedInstrument(Market.CRYPTO, "BTC");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		String createBody = objectMapper.writeValueAsString(new WatchlistItemCreateRequestBody(instrument.getId()));
		String createResponse = mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(createBody))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.instrumentId").value(instrument.getId()))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.name").value("삼성전자"))
			.andReturn().getResponse().getContentAsString();
		Long watchlistItemId = objectMapper.readTree(createResponse).get("watchlistItemId").asLong();

		mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new WatchlistItemCreateRequestBody(otherInstrument.getId()))))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].instrumentId").value(otherInstrument.getId()))
			.andExpect(jsonPath("$.content[1].instrumentId").value(instrument.getId()));

		mockMvc.perform(delete("/api/watchlist-items/{instrumentId}", instrument.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].instrumentId").value(otherInstrument.getId()));

		assertThat(watchlistItemRepository.findById(watchlistItemId)).isEmpty();
	}

	@Test
	void duplicateRegistrationIsRejectedWithConflict() throws Exception {
		User user = createUser("dup");
		Instrument instrument = seedInstrument(Market.STOCK, "000660");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
		String body = objectMapper.writeValueAsString(new WatchlistItemCreateRequestBody(instrument.getId()));

		mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isConflict());
	}

	@Test
	void unregisteringOthersItemOrMissingItemIsRejectedWithNotFound() throws Exception {
		User owner = createUser("owner");
		User stranger = createUser("stranger");
		Instrument instrument = seedInstrument(Market.STOCK, "035420");
		String ownerToken = jwtTokenProvider.issue(owner.getId(), owner.getRole()).accessToken();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();

		mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new WatchlistItemCreateRequestBody(instrument.getId()))))
			.andExpect(status().isCreated());

		mockMvc.perform(delete("/api/watchlist-items/{instrumentId}", instrument.getId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/watchlist-items/{instrumentId}", 999999L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	void registeredWatchlistItemSurvivesPersistenceContextClearSimulatingServerRestart() throws Exception {
		User user = createUser("persisted");
		Instrument instrument = seedInstrument(Market.STOCK, "005380");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(post("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new WatchlistItemCreateRequestBody(instrument.getId()))))
			.andExpect(status().isCreated());

		// 영속성 컨텍스트(1차 캐시)를 비워 이후 조회가 실제 MySQL 테이블을 다시 읽도록 강제한다.
		// 이는 애플리케이션 서버가 재시작되어 메모리 상태가 사라진 뒤 새 요청이 들어온 상황과 동등하다.
		entityManager.clear();

		List<WatchlistItem> reloaded = watchlistItemRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId());
		assertThat(reloaded).hasSize(1);
		assertThat(reloaded.get(0).getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(reloaded.get(0).getUserId()).isEqualTo(user.getId());

		mockMvc.perform(get("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].instrumentId").value(instrument.getId()));
	}

	@Test
	void emptyWatchlistReturnsOkWithEmptyArray() throws Exception {
		User user = createUser("empty");
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		mockMvc.perform(get("/api/watchlist-items")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			prefix + "-" + unique,
			NOW));
	}

	// V7 마이그레이션 시드 데이터(기존 심볼)를 그대로 사용한다 — 매 테스트 실행마다 새 종목을 만들면
	// uk_instruments_symbol unique 제약과 충돌할 수 있어 실제 서비스가 참조하는 시드 종목을 조회해 재사용한다.
	private Instrument seedInstrument(Market market, String symbol) {
		return instrumentRepository.findByMarketAndSymbol(market, symbol)
			.orElseThrow(() -> new IllegalStateException(
				"시드 종목을 찾을 수 없습니다: " + market + " " + symbol));
	}

	private record WatchlistItemCreateRequestBody(Long instrumentId) {
	}
}
