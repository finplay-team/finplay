package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.journal.dto.response.BuyJournalResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalResponse;
import com.finplay.api.domain.journal.service.JournalService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import java.math.BigDecimal;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SandboxDataCrossDomainExclusionIntegrationTest {

	private static final BigDecimal REAL_BUY_QUANTITY = new BigDecimal("0.02");
	private static final BigDecimal REAL_SELL_QUANTITY = new BigDecimal("0.01");
	private static final BigDecimal SANDBOX_BUY_QUANTITY = new BigDecimal("1");
	private static final BigDecimal SANDBOX_SELL_QUANTITY = new BigDecimal("0.6");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private JournalService journalService;

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
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		redisTemplate.delete("ranking:CRYPTO");
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("ranking:CRYPTO");
		cleanCommittedLedger();
	}

	private void cleanCommittedLedger() {
		if (!createdAccountIds.isEmpty()) {
			String accountIdIn = createdAccountIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from buy_trade_journals where buy_trade_id in "
				+ "(select id from trades where account_id in (" + accountIdIn + "))");
			jdbcTemplate.update("delete from sell_trade_journals where sell_trade_id in "
				+ "(select id from trades where account_id in (" + accountIdIn + "))");
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
	}

	@Test
	void accountWithBothRealAndSandboxHoldingsAndSalesExcludesSandboxFromAllFourEndpoints() throws Exception {
		User user = createUser("cross-excl");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument realInstrument = firstRealCryptoInstrument();
		Instrument sandboxInstrument = instrumentRepository
			.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		seedCryptoPrice(realInstrument, new BigDecimal("50000000"));
		OrderResponse realBuy = performOrderCall(accessToken, buyRequest(realInstrument.getId(), REAL_BUY_QUANTITY));
		seedCryptoPrice(realInstrument, new BigDecimal("80000000"));
		OrderResponse realSell = performOrderCall(accessToken, sellRequest(realInstrument.getId(), REAL_SELL_QUANTITY));
		assertThat(realSell.realizedPnl()).isNotNull();

		OrderResponse sandboxBuy = performOrderCall(
			accessToken, buyRequest(sandboxInstrument.getId(), SANDBOX_BUY_QUANTITY));
		OrderResponse sandboxSell = performOrderCall(
			accessToken, sellRequest(sandboxInstrument.getId(), SANDBOX_SELL_QUANTITY));

		BuyJournalResponse realBuyJournal = journalService.createBuyJournal(
			user.getId(), realBuy.tradeId(), "실제 종목 매수 회고");
		SellJournalResponse realSellJournal = journalService.createSellJournal(
			user.getId(), realSell.tradeId(), "실제 종목 매도 회고");
		journalService.createBuyJournal(user.getId(), sandboxBuy.tradeId(), "샌드박스 매수 회고");
		journalService.createSellJournal(user.getId(), sandboxSell.tradeId(), "샌드박스 매도 회고");

		mockMvc.perform(get("/api/holdings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].instrumentId").value(realInstrument.getId()));

		String journalBody = mockMvc.perform(get("/api/journal")
			.param("market", "CRYPTO")
			.param("limit", "20")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andReturn().getResponse().getContentAsString();
		assertThat(journalBody).contains("실제 종목 매수 회고", "실제 종목 매도 회고");
		assertThat(journalBody).doesNotContain("샌드박스 매수 회고", "샌드박스 매도 회고");
		assertThat(realBuyJournal.buyTradeId()).isEqualTo(realBuy.tradeId());
		assertThat(realSellJournal.sellTradeId()).isEqualTo(realSell.tradeId());

		RankingListResponse rankings = getRankings(accessToken);
		assertThat(rankings.content()).hasSize(1);
		RankingListItemResponse item = rankings.content().get(0);
		assertThat(item.nickname()).isEqualTo(user.getNickname());
		assertThat(item.realizedPnl()).isEqualTo(realSell.realizedPnl());

		MyRankingResponse myRanking = getMyRanking(accessToken);
		assertThat(myRanking.rank()).isEqualTo(1);
		assertThat(myRanking.realizedPnl()).isEqualTo(realSell.realizedPnl());
	}

	private OrderResponse performOrderCall(String accessToken, OrderCreateRequest request) throws Exception {
		String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, OrderResponse.class);
	}

	private RankingListResponse getRankings(String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
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

	private Instrument firstRealCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		Instrument real = cryptos.stream()
			.filter(instrument -> !instrument.isTutorialSample())
			.findFirst()
			.orElseThrow();
		return real;
	}

	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now());
	}

	private OrderCreateRequest buyRequest(Long instrumentId, BigDecimal quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.BUY, "MARKET", quantity);
	}

	private OrderCreateRequest sellRequest(Long instrumentId, BigDecimal quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.SELL, "MARKET", quantity);
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now()));
		createdUserIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, LocalDateTime.now()));
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
