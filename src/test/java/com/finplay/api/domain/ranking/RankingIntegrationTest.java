package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.listener.RankingEventListener;
import com.finplay.api.domain.ranking.service.RankingService;
import com.finplay.api.domain.ranking.store.RankingStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RankingIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@Autowired
	private RankingService rankingService;

	@Autowired
	private RankingEventListener rankingEventListener;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoSpyBean
	private RankingStore rankingStore;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();
	private final List<Long> createdInstrumentIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		cleanRankingKeys();
	}

	@AfterEach
	void tearDown() {
		reset(rankingStore);
		cleanRankingKeys();
		cleanCommittedLedger();
	}

	private void cleanCommittedLedger() {
		if (!createdAccountIds.isEmpty()) {
			String accountIdIn = createdAccountIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from trade_allocations where sell_trade_id in "
				+ "(select id from trades where account_id in (" + accountIdIn + "))");
			jdbcTemplate.update("delete from holding_lots where holding_id in "
				+ "(select id from holdings where account_id in (" + accountIdIn + "))");
			jdbcTemplate.update("delete from holdings where account_id in (" + accountIdIn + ")");
			jdbcTemplate.update("delete from trades where account_id in (" + accountIdIn + ")");
			jdbcTemplate.update("delete from orders where account_id in (" + accountIdIn + ")");
			createdAccountIds.clear();
		}
		if (!createdUserIds.isEmpty()) {
			String userIdIn = createdUserIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from tutorial_accounts where user_id in (" + userIdIn + ")");
			jdbcTemplate.update("delete from accounts where user_id in (" + userIdIn + ")");
			jdbcTemplate.update("delete from users where id in (" + userIdIn + ")");
			createdUserIds.clear();
		}
		if (!createdInstrumentIds.isEmpty()) {
			String instrumentIdIn = createdInstrumentIds.stream()
				.map(String::valueOf)
				.collect(Collectors.joining(","));
			jdbcTemplate.update("delete from instruments where id in (" + instrumentIdIn + ")");
			createdInstrumentIds.clear();
		}
	}

	@Test
	void sellExecutionCommitsAndAppearsInRankingByResponseTime() throws Exception {
		User user = createUser("rank-sell");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();

		seedCryptoPrice(instrument, new BigDecimal("50000000"));
		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		seedCryptoPrice(instrument, new BigDecimal("80000000"));
		String sellBody = performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		OrderResponse sellResponse = objectMapper.readValue(sellBody, OrderResponse.class);
		assertThat(sellResponse.realizedPnl()).isNotNull();

		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", null);

		assertThat(rankings.market()).isEqualTo("CRYPTO");
		assertThat(rankings.content()).hasSize(1);
		RankingListItemResponse item = rankings.content().get(0);
		assertThat(item.rank()).isEqualTo(1);
		assertThat(item.nickname()).isEqualTo(user.getNickname());
		assertThat(item.realizedPnl()).isEqualTo(sellResponse.realizedPnl());
	}

	@Test
	void eventOrderReversalStillConvergesToLatestDbRealizedPnl() {
		User user = createUser("rank-reorder");
		Account account = createAccount(user);

		addRealizedPnlAndCommit(account.getId(), 100L);
		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));
		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(100.0);

		long finalRealizedPnl = addRealizedPnlAndCommit(account.getId(), 50L);

		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));
		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));

		assertThat(finalRealizedPnl).isEqualTo(150L);
		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(150.0);
	}

	@Test
	void refreshScoreReadsLatestDbValueEvenWhenCallerHasStalePersistenceContext() {
		User user = createUser("rank-stale-pc");
		Account account = createAccount(user);
		addRealizedPnlAndCommit(account.getId(), 100L);

		TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
		outerTx.executeWithoutResult(status -> {
			Account cached = accountRepository.findById(account.getId()).orElseThrow();
			assertThat(cached.getRealizedPnl()).isEqualTo(100L);

			updateRealizedPnlInNewTransactionAndCommit(account.getId(), 200L);

			rankingService.refreshScore(account.getId());
		});

		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(200.0);
	}

	@Test
	void afterCommitListenerAppliesLatestDbValueWhenEventPublishedThroughRealTransactionalWiring() {
		User user = createUser("rank-real-wiring");
		Account account = createAccount(user);
		addRealizedPnlAndCommit(account.getId(), 100L);

		TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
		outerTx.executeWithoutResult(status -> {
			Account cached = accountRepository.findById(account.getId()).orElseThrow();
			assertThat(cached.getRealizedPnl()).isEqualTo(100L);

			updateRealizedPnlInNewTransactionAndCommit(account.getId(), 300L);

			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		});

		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(300.0);
	}

	private void updateRealizedPnlInNewTransactionAndCommit(Long accountId, long newRealizedPnl) {
		TransactionTemplate newTx = new TransactionTemplate(transactionManager);
		newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		newTx.executeWithoutResult(status -> {
			Account account = accountRepository.findById(accountId).orElseThrow();
			long delta = newRealizedPnl - account.getRealizedPnl();
			account.addRealizedPnl(delta);
			accountRepository.saveAndFlush(account);
		});
	}

	@Test
	void sellRejectedByInsufficientQuantityLeavesRankingUntouched() throws Exception {
		User user = createUser("rank-reject");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QTY"));

		assertThat(scoreOf("CRYPTO", account.getId())).isNull();
	}

	@Test
	void rankingUpdateFailureDoesNotAffectSellExecution() throws Exception {
		User user = createUser("rank-redisdown");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		doThrow(new RuntimeException("redis down"))
			.when(rankingStore)
			.addScoreWithRetry(any(), any(), anyLong());

		seedCryptoPrice(instrument, new BigDecimal("80000000"));
		performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isCreated());

		assertThat(scoreOf("CRYPTO", account.getId())).isNull();
	}

	@Test
	void accountWithNoSellHistoryIsExcludedFromRankingList() throws Exception {
		User user = createUser("rank-nosell");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", 50);

		assertThat(rankings.content()).noneMatch(item -> item.nickname().equals(user.getNickname()));
	}

	@Test
	void tiedRealizedPnlAccountsShareRankAndNextRankSkipsByTieCount() throws Exception {
		User userA = createUser("rank-tie-a");
		User userB = createUser("rank-tie-b");
		User userC = createUser("rank-tie-c");
		Account accountA = createAccount(userA);
		Account accountB = createAccount(userB);
		Account accountC = createAccount(userC);

		addRealizedPnlAndCommit(accountA.getId(), 500_000L);
		addRealizedPnlAndCommit(accountB.getId(), 500_000L);
		addRealizedPnlAndCommit(accountC.getId(), 300_000L);
		rankingService.refreshScore(accountA.getId());
		rankingService.refreshScore(accountB.getId());
		rankingService.refreshScore(accountC.getId());

		String accessToken = issueAccessToken(userA);
		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", 50);

		assertThat(rankOf(rankings, userA.getNickname())).isEqualTo(1);
		assertThat(rankOf(rankings, userB.getNickname())).isEqualTo(1);
		assertThat(rankOf(rankings, userC.getNickname())).isEqualTo(3);
	}

	@Test
	void getMyRankingReturnsAccurateRankEvenOutsideDefaultListLimit() throws Exception {
		User lowestRankedUser = null;
		String lowestRankedAccessToken = null;
		for (int i = 1; i <= 11; i++) {
			User user = createUser("rank-outside-" + i);
			Account account = createAccount(user);
			long realizedPnl = (12 - i) * 100_000L;
			addRealizedPnlAndCommit(account.getId(), realizedPnl);
			rankingService.refreshScore(account.getId());
			if (i == 11) {
				lowestRankedUser = user;
				lowestRankedAccessToken = issueAccessToken(user);
			}
		}

		MyRankingResponse response = getMyRanking(lowestRankedAccessToken, "CRYPTO");

		assertThat(response.rank()).isEqualTo(11);
		assertThat(response.realizedPnl()).isEqualTo(100_000L);
		assertThat(response.nickname()).isEqualTo(lowestRankedUser.getNickname());
	}

	@Test
	void getRankingsReturnsUnavailableStatusInsteadOfFiveHundredWhenRankingStoreIsUnreachable() throws Exception {
		User user = createUser("rank-redis-outage");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		doThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"))
			.when(rankingStore)
			.topN(any(), anyInt());

		mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getMyRankingReturnsUnavailableStatusInsteadOfFiveHundredWhenRankingStoreIsUnreachable() throws Exception {
		User user = createUser("rank-me-redis-outage");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		doThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"))
			.when(rankingStore)
			.score(any(), any());

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.rank").doesNotExist())
			.andExpect(jsonPath("$.nickname").value(user.getNickname()));
	}

	@Test
	void sellingOnlyTutorialSampleInstrumentLeavesAccountOutOfRankingList() throws Exception {
		User user = createUser("rank-tutorial-only");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("rank-tutorial-only");

		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isCreated());

		assertThat(scoreOf("CRYPTO", account.getId())).isNull();
		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", 50);
		assertThat(rankings.content()).noneMatch(item -> item.nickname().equals(user.getNickname()));
	}

	private MyRankingResponse getMyRanking(String accessToken, String market) throws Exception {
		String body = mockMvc.perform(get("/api/rankings/me")
			.param("market", market)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, MyRankingResponse.class);
	}

	private int rankOf(RankingListResponse rankings, String nickname) {
		return rankings.content().stream()
			.filter(item -> item.nickname().equals(nickname))
			.findFirst()
			.orElseThrow(() -> new AssertionError("랭킹 목록에서 닉네임을 찾을 수 없음: " + nickname))
			.rank();
	}

	private long addRealizedPnlAndCommit(Long accountId, long delta) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		account.addRealizedPnl(delta);
		Account saved = accountRepository.saveAndFlush(account);
		return saved.getRealizedPnl();
	}

	private Double scoreOf(String market, Long accountId) {
		return redisTemplate.opsForZSet().score("ranking:" + market, String.valueOf(accountId));
	}

	private void cleanRankingKeys() {
		redisTemplate.delete("ranking:STOCK");
		redisTemplate.delete("ranking:CRYPTO");
	}

	private ResultActions performOrder(String accessToken, OrderCreateRequest request) throws Exception {
		return mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
	}

	private RankingListResponse getRankings(String accessToken, String market, Integer limit) throws Exception {
		var requestBuilder = get("/api/rankings")
			.param("market", market)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		if (limit != null) {
			requestBuilder = requestBuilder.param("limit", String.valueOf(limit));
		}
		String body = mockMvc.perform(requestBuilder)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, RankingListResponse.class);
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), scenario,
			BigDecimal.ONE, 0L, true, LocalDateTime.now(clock));
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		createdInstrumentIds.add(instrument.getId());
		return instrument;
	}

	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
		createdUserIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, LocalDateTime.now(clock)));
		createdAccountIds.add(account.getId());
		return account;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
