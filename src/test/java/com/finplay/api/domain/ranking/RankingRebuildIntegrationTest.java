package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
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
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.entity.RankingStatus;
import com.finplay.api.domain.ranking.service.RankingRebuildService;
import com.finplay.api.domain.ranking.store.RankingStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RankingRebuildIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
	private static final com.finplay.api.domain.market.entity.Market CRYPTO = Market.CRYPTO;
	private static final com.finplay.api.domain.market.entity.Market STOCK = Market.STOCK;
	private static final BigDecimal BUY_PRICE = new BigDecimal("50000000");
	private static final String BUY_QUANTITY = "0.02";
	private static final String SELL_QUANTITY = "0.01";

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
	private ObjectMapper objectMapper;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RankingRebuildService rankingRebuildService;

	@Autowired
	private RankingStore rankingStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		if (!createdAccountIds.isEmpty()) {
			String[] members = createdAccountIds.stream().map(String::valueOf).toArray(String[]::new);
			redisTemplate.opsForZSet().remove(rankingKey(CRYPTO), (Object[])members);
			redisTemplate.opsForZSet().remove(rankingKey(STOCK), (Object[])members);

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
			jdbcTemplate.update("delete from accounts where user_id in (" + userIdIn + ")");
			jdbcTemplate.update("delete from users where id in (" + userIdIn + ")");
			createdUserIds.clear();
		}
	}

	@Test
	void rebuildRestoresIdenticalRanksAndAmountsAfterZsetIsLost() throws Exception {
		SoldAccount tieA = sellForNaturalPnl("rbld-tie-a", new BigDecimal("80000000"));
		SoldAccount tieB = sellForNaturalPnl("rbld-tie-b", new BigDecimal("80000000"));
		SoldAccount lower = sellForNaturalPnl("rbld-lower", new BigDecimal("60000000"));

		Long liveScoreA = rankingStore.score(CRYPTO, tieA.accountId());
		Long liveScoreB = rankingStore.score(CRYPTO, tieB.accountId());
		Long liveScoreLower = rankingStore.score(CRYPTO, lower.accountId());
		assertThat(liveScoreA).isNotNull();
		assertThat(liveScoreA).isEqualTo(liveScoreB);
		assertThat(liveScoreLower).isLessThan(liveScoreA);

		RankingListResponse before = getRankings(tieA.accessToken());
		assertThat(before.status()).isEqualTo(RankingStatus.READY);
		assertThat(rankOf(before, tieA.nickname())).isEqualTo(rankOf(before, tieB.nickname()));
		assertThat(rankOf(before, lower.nickname())).isGreaterThan(rankOf(before, tieA.nickname()));

		deleteRankingKey(CRYPTO);
		assertThat(rankingStore.score(CRYPTO, tieA.accountId())).isNull();

		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, tieA.accountId())).isEqualTo(liveScoreA);
		assertThat(rankingStore.score(CRYPTO, tieB.accountId())).isEqualTo(liveScoreB);
		assertThat(rankingStore.score(CRYPTO, lower.accountId())).isEqualTo(liveScoreLower);

		RankingListResponse after = getRankings(tieA.accessToken());
		assertThat(after.status()).isEqualTo(RankingStatus.READY);
		assertThat(after.content()).isEqualTo(before.content());
	}

	@Test
	void rebuiltScoreComesFromAccountsRealizedPnlColumn() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-column", new BigDecimal("80000000"));
		Long staleScore = rankingStore.score(CRYPTO, sold.accountId());
		assertThat(staleScore).isNotNull();

		long divergedRealizedPnl = 777_777L;
		setRealizedPnlAndCommit(sold.accountId(), divergedRealizedPnl);
		assertThat(divergedRealizedPnl).isNotEqualTo(staleScore);
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(staleScore);

		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(divergedRealizedPnl);
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(realizedPnlColumnOf(sold.accountId()));
	}

	@Test
	void rebuildIncludesSoldAccountWhoseRealizedPnlIsExactlyZero() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-zero", new BigDecimal("80000000"));
		setRealizedPnlAndCommit(sold.accountId(), 0L);
		assertThat(realizedPnlColumnOf(sold.accountId())).isZero();
		deleteRankingKey(CRYPTO);

		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isZero();
	}

	@Test
	void rebuildExcludesAccountWithoutAnySellHistory() throws Exception {
		User user = createUser("rbld-nosell");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, BUY_PRICE);
		performOrder(accessToken, buyRequest(instrument.getId())).andExpect(status().isCreated());

		deleteRankingKey(CRYPTO);
		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, account.getId())).isNull();
	}

	@Test
	void replaceAllDeletesLiveKeyInsteadOfFailingRenameWhenNoAccountsQualify() {
		String liveKey = rankingKey(STOCK);
		String rebuildKey = liveKey + ":rebuild";
		redisTemplate.delete(rebuildKey);
		redisTemplate.opsForZSet().add(liveKey, "999999", 12_345d);
		assertThat(redisTemplate.hasKey(liveKey)).isTrue();

		rankingStore.replaceAll(STOCK, List.of());

		assertThat(redisTemplate.hasKey(liveKey)).isFalse();
		assertThat(redisTemplate.hasKey(rebuildKey)).isFalse();
	}

	@Test
	void bothEndpointsReturnOkWithRebuildingStatusBeforeRebuildAndReadyAfter() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-status", new BigDecimal("80000000"));
		deleteRankingKey(CRYPTO);

		mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sold.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REBUILDING"))
			.andExpect(jsonPath("$.content").isEmpty());

		MyRankingResponse lost = getMyRanking(sold.accessToken());
		assertThat(lost.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(lost.rank()).isNull();
		assertThat(lost.nickname()).isEqualTo(sold.nickname());

		rankingRebuildService.rebuild(CRYPTO);

		MyRankingResponse restored = getMyRanking(sold.accessToken());
		assertThat(restored.status()).isEqualTo(RankingStatus.READY);
		assertThat(restored.rank()).isNotNull();
		assertThat(restored.realizedPnl()).isEqualTo(realizedPnlColumnOf(sold.accountId()));
	}

	@Test
	void ledgerIsUnchangedAcrossRebuild() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-ledger", new BigDecimal("80000000"));
		LedgerSnapshot before = snapshotLedgerOf(sold.accountId());
		assertThat(before.orders()).isNotEmpty();
		assertThat(before.trades()).hasSize(2);
		assertThat(before.holdings()).isNotEmpty();
		assertThat(before.holdingLots()).isNotEmpty();

		deleteRankingKey(CRYPTO);
		rankingRebuildService.rebuild(CRYPTO);
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isNotNull();

		assertThat(snapshotLedgerOf(sold.accountId())).isEqualTo(before);
	}

	@Test
	void ledgerSnapshotDetectsAChangeInEveryTrackedTable() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-snapshot", new BigDecimal("80000000"));
		Long accountId = sold.accountId();
		LedgerSnapshot baseline = snapshotLedgerOf(accountId);

		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET cash_balance = cash_balance + 1 WHERE id = ?",
			"UPDATE accounts SET cash_balance = cash_balance - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET realized_pnl = realized_pnl + 1 WHERE id = ?",
			"UPDATE accounts SET realized_pnl = realized_pnl - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET reserved_cash = reserved_cash + 1 WHERE id = ?",
			"UPDATE accounts SET reserved_cash = reserved_cash - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE orders SET quantity = quantity + 1 WHERE account_id = ?",
			"UPDATE orders SET quantity = quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE orders SET limit_price = 1 WHERE account_id = ?",
			"UPDATE orders SET limit_price = NULL WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE trades SET fee = fee + 1 WHERE account_id = ?",
			"UPDATE trades SET fee = fee - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holdings SET quantity = quantity + 1 WHERE account_id = ?",
			"UPDATE holdings SET quantity = quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holdings SET reserved_quantity = reserved_quantity + 1 WHERE account_id = ?",
			"UPDATE holdings SET reserved_quantity = reserved_quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holding_lots SET remaining_quantity = remaining_quantity + 1"
				+ " WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
			"UPDATE holding_lots SET remaining_quantity = remaining_quantity - 1"
				+ " WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)");
	}

	private void assertSnapshotDetects(Long accountId, LedgerSnapshot baseline, String mutate, String restore) {
		int mutated = jdbcTemplate.update(mutate, accountId);
		try {
			assertThat(mutated).as("변형이 한 행도 바꾸지 못했다 — 픽스처가 비어 있다: %s", mutate).isPositive();
			assertThat(snapshotLedgerOf(accountId))
				.as("스냅샷이 이 변경을 잡아내지 못한다 — 불변 단정의 감시 범위 밖이다: %s", mutate)
				.isNotEqualTo(baseline);
		} finally {
			jdbcTemplate.update(restore, accountId);
		}
		assertThat(snapshotLedgerOf(accountId)).as("원복되지 않았다: %s", restore).isEqualTo(baseline);
	}


	private SoldAccount sellForNaturalPnl(String scenario, BigDecimal sellPrice) throws Exception {
		User user = createUser(scenario);
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();

		seedCryptoPrice(instrument, BUY_PRICE);
		performOrder(accessToken, buyRequest(instrument.getId())).andExpect(status().isCreated());

		seedCryptoPrice(instrument, sellPrice);
		performOrder(accessToken, sellRequest(instrument.getId())).andExpect(status().isCreated());

		return new SoldAccount(account.getId(), user.getNickname(), accessToken);
	}

	private void setRealizedPnlAndCommit(Long accountId, long target) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		account.addRealizedPnl(target - account.getRealizedPnl());
		accountRepository.saveAndFlush(account);
	}

	private long realizedPnlColumnOf(Long accountId) {
		return jdbcTemplate.queryForObject("SELECT realized_pnl FROM accounts WHERE id = ?", Long.class, accountId);
	}

	private LedgerSnapshot snapshotLedgerOf(Long accountId) {
		return new LedgerSnapshot(
			jdbcTemplate.queryForList(
				"SELECT id, user_id, account_id, instrument_id, side, order_type, status, quantity, limit_price"
					+ " FROM orders WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT id, order_id, account_id, instrument_id, side, price, quantity, amount, fee, realized_pnl"
					+ " FROM trades WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT id, cash_balance, reserved_cash, realized_pnl FROM accounts WHERE id = ?", accountId),
			jdbcTemplate.queryForList(
				"SELECT id, instrument_id, quantity, reserved_quantity, average_price, is_active"
					+ " FROM holdings WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT l.id, l.holding_id, l.original_quantity, l.remaining_quantity, l.unit_cost, l.buy_fee"
					+ " FROM holding_lots l JOIN holdings h ON h.id = l.holding_id"
					+ " WHERE h.account_id = ? ORDER BY l.id",
				accountId));
	}

	private record LedgerSnapshot(
		List<Map<String, Object>> orders,
		List<Map<String, Object>> trades,
		List<Map<String, Object>> accounts,
		List<Map<String, Object>> holdings,
		List<Map<String, Object>> holdingLots) {
	}

	private record SoldAccount(Long accountId, String nickname, String accessToken) {
	}


	private RankingListResponse getRankings(String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.param("limit", "50")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, RankingListResponse.class);
	}

	private MyRankingResponse getMyRanking(String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
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

	private void deleteRankingKey(com.finplay.api.domain.market.entity.Market market) {
		redisTemplate.delete(rankingKey(market));
	}

	private String rankingKey(com.finplay.api.domain.market.entity.Market market) {
		return "ranking:" + market.name();
	}

	private ResultActions performOrder(String accessToken, OrderCreateRequest request) throws Exception {
		return mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
	}

	private OrderCreateRequest buyRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.BUY, "MARKET",
			new BigDecimal(BUY_QUANTITY));
	}

	private OrderCreateRequest sellRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.SELL, "MARKET",
			new BigDecimal(SELL_QUANTITY));
	}

	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
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
		Account account = accountRepository.saveAndFlush(Account.create(user, CRYPTO, LocalDateTime.now(clock)));
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
