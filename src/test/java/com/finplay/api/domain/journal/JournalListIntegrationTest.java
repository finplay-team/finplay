package com.finplay.api.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.domain.journal.repository.SellTradeJournalRepository;
import com.finplay.api.domain.journal.service.JournalService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class JournalListIntegrationTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);
	private static final DateTimeFormatter RESPONSE_DATETIME_FORMAT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private JournalService journalService;

	@Autowired
	private TestClock clock;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private BuyTradeJournalRepository buyTradeJournalRepository;

	@Autowired
	private SellTradeJournalRepository sellTradeJournalRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Test
	void mixedBuyAndSellJournalsAreOrderedByCreatedAtDescWithCorrectTypeAndNullCounterpartId() throws Exception {
		User user = createUser("lst-mixed");
		createAccount(user, Market.STOCK);

		Long buyTradeId = createBuyTrade(user, "LSTMXB");
		TradePair pair = createBuyThenSellTradePair(user, "LSTMXS");

		LocalDateTime buyCreatedAt = BASE_NOW.plusHours(1);
		LocalDateTime sellCreatedAt = BASE_NOW.plusHours(2);
		clock.set(buyCreatedAt);
		journalService.createBuyJournal(user.getId(), buyTradeId, "매수 회고");
		clock.set(sellCreatedAt);
		journalService.createSellJournal(user.getId(), pair.sellTradeId(), "매도 회고");

		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].journalType").value("SELL"))
			.andExpect(jsonPath("$.content[0].sellTradeId").value(pair.sellTradeId()))
			.andExpect(jsonPath("$.content[0].buyTradeId").doesNotExist())
			.andExpect(jsonPath("$.content[0].content").value("매도 회고"))
			.andExpect(jsonPath("$.content[0].createdAt").value(sellCreatedAt.format(RESPONSE_DATETIME_FORMAT)))
			.andExpect(jsonPath("$.content[1].journalType").value("BUY"))
			.andExpect(jsonPath("$.content[1].buyTradeId").value(buyTradeId))
			.andExpect(jsonPath("$.content[1].sellTradeId").doesNotExist())
			.andExpect(jsonPath("$.content[1].content").value("매수 회고"))
			.andExpect(jsonPath("$.content[1].createdAt").value(buyCreatedAt.format(RESPONSE_DATETIME_FORMAT)))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void marketFilterExcludesOtherMarketJournalsOfSameUser() throws Exception {
		User user = createUser("lst-market");
		createAccount(user, Market.STOCK);
		createAccount(user, Market.CRYPTO);

		Long stockBuyTradeId = createBuyTrade(user, "LSTMKS");
		Long cryptoBuyTradeId = createCryptoBuyTrade(user, "LSTMKC");

		clock.set(BASE_NOW.plusHours(1));
		journalService.createBuyJournal(user.getId(), stockBuyTradeId, "주식 회고");
		clock.set(BASE_NOW.plusHours(2));
		journalService.createBuyJournal(user.getId(), cryptoBuyTradeId, "코인 회고");

		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].buyTradeId").value(stockBuyTradeId))
			.andExpect(jsonPath("$.content[0].content").value("주식 회고"));

		mockMvc.perform(getJournalList(accessToken, "CRYPTO", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].buyTradeId").value(cryptoBuyTradeId))
			.andExpect(jsonPath("$.content[0].content").value("코인 회고"));
	}

	@Test
	void cursorPaginationAcrossPagesHasNoDuplicatesOrGapsAndLastPageHasNoNext() throws Exception {
		User user = createUser("lst-page");
		createAccount(user, Market.STOCK);

		Long buy1 = createBuyTrade(user, "LSTPG1");
		Long buy2 = createBuyTrade(user, "LSTPG2");
		TradePair pair1 = createBuyThenSellTradePair(user, "LSTPG3");
		Long buy4 = createBuyTrade(user, "LSTPG4");
		TradePair pair2 = createBuyThenSellTradePair(user, "LSTPG5");

		clock.set(BASE_NOW.plusHours(1));
		journalService.createBuyJournal(user.getId(), buy1, "1시간 후 매수 회고");
		clock.set(BASE_NOW.plusHours(2));
		journalService.createBuyJournal(user.getId(), buy2, "2시간 후 매수 회고");
		clock.set(BASE_NOW.plusHours(3));
		journalService.createSellJournal(user.getId(), pair1.sellTradeId(), "3시간 후 매도 회고");
		clock.set(BASE_NOW.plusHours(4));
		journalService.createBuyJournal(user.getId(), buy4, "4시간 후 매수 회고");
		clock.set(BASE_NOW.plusHours(5));
		journalService.createSellJournal(user.getId(), pair2.sellTradeId(), "5시간 후 매도 회고");

		String accessToken = issueAccessToken(user);

		List<String> paged = collectAllIdentifiersByCursor(accessToken, "STOCK", 2);
		List<String> singlePage = collectSinglePageIdentifiers(accessToken, "STOCK", 20);

		assertThat(paged).hasSize(5).doesNotHaveDuplicates();
		assertThat(paged).containsExactlyElementsOf(singlePage);
	}

	@Test
	void cursorPaginationTieBreakSplitsSameTimestampBuyAndSellAcrossPageBoundary() throws Exception {
		User user = createUser("lst-tie");
		createAccount(user, Market.STOCK);

		Long oldestBuyTradeId = createBuyTrade(user, "LSTTI1");
		Long tiedLowBuyTradeId = createBuyTrade(user, "LSTTI2");
		TradePair tiedPair = createBuyThenSellTradePair(user, "LSTTI3");
		Long newestBuyTradeId = createBuyTrade(user, "LSTTI4");
		assertThat(tiedPair.sellTradeId()).isGreaterThan(tiedLowBuyTradeId);

		LocalDateTime t0 = BASE_NOW.plusHours(1);
		LocalDateTime t1 = BASE_NOW.plusHours(2);
		LocalDateTime t2 = BASE_NOW.plusHours(3);

		clock.set(t0);
		journalService.createBuyJournal(user.getId(), oldestBuyTradeId, "가장 오래된 매수 회고");
		clock.set(t1);
		journalService.createBuyJournal(user.getId(), tiedLowBuyTradeId, "동시각 매수 회고");
		journalService.createSellJournal(user.getId(), tiedPair.sellTradeId(), "동시각 매도 회고");
		clock.set(t2);
		journalService.createBuyJournal(user.getId(), newestBuyTradeId, "가장 최신 매수 회고");

		String accessToken = issueAccessToken(user);

		String page1Body = mockMvc.perform(getJournalList(accessToken, "STOCK", null, 2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].journalType").value("BUY"))
			.andExpect(jsonPath("$.content[0].buyTradeId").value(newestBuyTradeId))
			.andExpect(jsonPath("$.content[1].journalType").value("SELL"))
			.andExpect(jsonPath("$.content[1].sellTradeId").value(tiedPair.sellTradeId()))
			.andExpect(jsonPath("$.hasNext").value(true))
			.andReturn().getResponse().getContentAsString();
		String nextCursor = objectMapper.readTree(page1Body).get("nextCursor").asText();
		assertThat(nextCursor).isNotBlank();

		mockMvc.perform(getJournalList(accessToken, "STOCK", nextCursor, 2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andExpect(jsonPath("$.content[0].journalType").value("BUY"))
			.andExpect(jsonPath("$.content[0].buyTradeId").value(tiedLowBuyTradeId))
			.andExpect(jsonPath("$.content[1].journalType").value("BUY"))
			.andExpect(jsonPath("$.content[1].buyTradeId").value(oldestBuyTradeId))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void limitOutOfRangeReturns400WithoutClamping() throws Exception {
		User user = createUser("lst-limit");
		createAccount(user, Market.STOCK);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 101))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void missingOrUnsupportedMarketLiteralReturns400() throws Exception {
		User user = createUser("lst-nomkt");
		createAccount(user, Market.STOCK);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, null, null, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(getJournalList(accessToken, "NOT_A_MARKET", null, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void corruptedCursorReturns400() throws Exception {
		User user = createUser("lst-cursor");
		createAccount(user, Market.STOCK);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", "garbage", null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(getJournalList(accessToken, "STOCK", "not-a-date_5", null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(getJournalList(accessToken, "STOCK", "2026-08-04T10:12:33_notanumber", null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void accountNotFoundForRequestedMarketReturns404() throws Exception {
		User user = createUser("lst-noacct");
		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, null))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		mockMvc.perform(getJournalList(null, "STOCK", null, null))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void otherUsersJournalsDoNotLeakIntoOwnList() throws Exception {
		User owner = createUser("lst-owner");
		createAccount(owner, Market.STOCK);
		Long ownerBuyTradeId = createBuyTrade(owner, "LSTOWN");

		User other = createUser("lst-other");
		createAccount(other, Market.STOCK);
		Long otherBuyTradeId = createBuyTrade(other, "LSTOTH");

		clock.set(BASE_NOW.plusHours(1));
		journalService.createBuyJournal(owner.getId(), ownerBuyTradeId, "소유자 회고");
		clock.set(BASE_NOW.plusHours(2));
		journalService.createBuyJournal(other.getId(), otherBuyTradeId, "타인 회고");

		String ownerAccessToken = issueAccessToken(owner);

		mockMvc.perform(getJournalList(ownerAccessToken, "STOCK", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].buyTradeId").value(ownerBuyTradeId))
			.andExpect(jsonPath("$.content[0].content").value("소유자 회고"));
	}

	@Test
	void userWithNoJournalsReturnsEmptyPage() throws Exception {
		User user = createUser("lst-empty");
		createAccount(user, Market.STOCK);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	void journalIdKeyIsNeverExposedInResponseBody() throws Exception {
		User user = createUser("lst-noid");
		createAccount(user, Market.STOCK);

		Long buyTradeId = createBuyTrade(user, "LSTNID");
		TradePair pair = createBuyThenSellTradePair(user, "LSTNI2");
		clock.set(BASE_NOW.plusHours(1));
		journalService.createBuyJournal(user.getId(), buyTradeId, "매수 회고");
		clock.set(BASE_NOW.plusHours(2));
		journalService.createSellJournal(user.getId(), pair.sellTradeId(), "매도 회고");

		String accessToken = issueAccessToken(user);

		String body = mockMvc.perform(getJournalList(accessToken, "STOCK", null, 20))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(2))
			.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("\"journalId\"");
	}

	@Test
	void getRequestDoesNotMutateLedgerOrJournalTables() throws Exception {
		User user = createUser("lst-invariant");
		Account account = createAccount(user, Market.STOCK);

		TradePair pair = createBuyThenSellTradePair(user, "LSTINV");
		clock.set(BASE_NOW.plusHours(1));
		journalService.createBuyJournal(user.getId(), pair.buyTradeId(), "매수 회고");
		clock.set(BASE_NOW.plusHours(2));
		journalService.createSellJournal(user.getId(), pair.sellTradeId(), "매도 회고");

		String accessToken = issueAccessToken(user);

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 20)).andExpect(status().isOk());
		mockMvc.perform(getJournalList(accessToken, "STOCK", null, 0)).andExpect(status().isBadRequest());
		mockMvc.perform(getJournalList(null, "STOCK", null, null)).andExpect(status().isUnauthorized());

		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	private List<String> collectAllIdentifiersByCursor(String accessToken, String market, int limit) throws Exception {
		List<String> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20);

			String body = mockMvc.perform(getJournalList(accessToken, market, cursor, limit))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			JsonNode root = objectMapper.readTree(body);
			root.get("content").forEach(node -> ids.add(identifierOf(node)));
			hasNext = root.get("hasNext").asBoolean();
			if (hasNext) {
				cursor = root.get("nextCursor").asText();
			} else {
				assertThat(root.get("nextCursor").isNull()).isTrue();
			}
		}
		return ids;
	}

	private List<String> collectSinglePageIdentifiers(String accessToken, String market, int limit) throws Exception {
		String body = mockMvc.perform(getJournalList(accessToken, market, null, limit))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode root = objectMapper.readTree(body);
		List<String> ids = new ArrayList<>();
		root.get("content").forEach(node -> ids.add(identifierOf(node)));
		return ids;
	}

	private String identifierOf(JsonNode node) {
		String journalType = node.get("journalType").asText();
		long tradeId = "BUY".equals(journalType) ? node.get("buyTradeId").asLong() : node.get("sellTradeId").asLong();
		return journalType + ":" + tradeId;
	}

	private MockHttpServletRequestBuilder getJournalList(String accessToken, String market, String cursor,
		Integer limit) {
		MockHttpServletRequestBuilder request = get("/api/journal");
		if (accessToken != null) {
			request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		}
		if (market != null) {
			request = request.param("market", market);
		}
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		if (limit != null) {
			request = request.param("limit", String.valueOf(limit));
		}
		return request;
	}

	private Long createBuyTrade(User user, String instrumentPrefix) {
		clock.set(BASE_NOW);
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-" + UUID.randomUUID(),
			buyRequest(Market.STOCK, instrument.getId(), "10"));
		return response.tradeId();
	}

	private Long createCryptoBuyTrade(User user, String instrumentPrefix) {
		clock.set(BASE_NOW);
		Instrument instrument = createCryptoInstrument(instrumentPrefix);
		seedCryptoPrice(instrument, new BigDecimal("50000000"));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-" + UUID.randomUUID(),
			buyRequest(Market.CRYPTO, instrument.getId(), "0.01"));
		return response.tradeId();
	}

	private TradePair createBuyThenSellTradePair(User user, String instrumentPrefix) {
		clock.set(BASE_NOW);
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		OrderResponse buy = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(Market.STOCK, instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		OrderResponse sell = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-sell-" + UUID.randomUUID(),
			sellRequest(Market.STOCK, instrument.getId(), "5"));
		return new TradePair(buy.tradeId(), sell.tradeId());
	}

	private record TradePair(Long buyTradeId, Long sellTradeId) {
	}

	private OrderCreateRequest buyRequest(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user, com.finplay.api.domain.market.entity.Market market) {
		return accountRepository.saveAndFlush(Account.create(user, market, BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private LedgerSnapshot captureLedger(Long accountId) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		return new LedgerSnapshot(
			orderRepository.count(),
			tradeRepository.count(),
			holdingRepository.count(),
			holdingLotRepository.count(),
			tradeAllocationRepository.count(),
			buyTradeJournalRepository.count(),
			sellTradeJournalRepository.count(),
			account.getCashBalance(),
			account.getRealizedPnl());
	}

	private record LedgerSnapshot(
		long orders,
		long trades,
		long holdings,
		long holdingLots,
		long tradeAllocations,
		long buyJournals,
		long sellJournals,
		long cashBalance,
		long realizedPnl) {
	}

}
