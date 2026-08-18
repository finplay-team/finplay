// spec 033: 실제 종목 + 샌드박스 종목을 함께 보유·매도한 계좌 하나가 GET /api/holdings·GET /api/journal·
// GET /api/rankings·GET /api/rankings/me 네 응답 모두에서 샌드박스 기원 데이터를 제외하는지 확인하는
// 통합 테스트다(spec SANDBOX-EXCL-001·002·003, 완료 조건).
package com.finplay.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalResponse;
import com.finplay.api.journal.service.JournalService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.ranking.dto.response.MyRankingResponse;
import com.finplay.api.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
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

// GET /api/rankings·GET /api/rankings/me는 AFTER_COMMIT 리스너(RankingEventListener)가 실제 커밋 이후에만
// ZSET을 갱신하므로, RankingIntegrationTest와 같은 이유로 이 클래스도 @Transactional을 붙이지 않는다 —
// 대신 이 클래스가 만든 계좌·회원·원장·투자일기를 @AfterEach에서 명시적으로 정리한다(agent-mistakes.md
// 2026-07-30 항목과 동일 관례). 새 Instrument는 만들지 않고 시드 코인(실제 종목)과 V32/V33이 만든
// SANDBOX_COIN_1(샌드박스 종목)을 그대로 재사용해 InstrumentRepositoryTest의 절대개수 단정을 건드리지 않는다.
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

	// RankingIntegrationTest.cleanCommittedLedger와 동일 관례 — 이 클래스가 만든 투자일기(buy_trade_journals·
	// sell_trade_journals)도 함께 지운다(RankingIntegrationTest는 저널을 만들지 않아 대상이 아니었다).
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
			// 047 이후 이 테스트가 쓰는 튜토리얼 종목 매수(isTutorialSample()) 흐름이 튜토리얼 계좌를
			// get-or-create하면서 남기는 행이다 — 정리하지 않으면 users 삭제가 fk_tutorial_accounts_user
			// 위반으로 실패한다(이슈 #450 후속 회귀 확인 중 발견, 다른 3개 클래스와 동일한 정리 누락).
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

		// 실제 종목: 매수 후 값이 오른 시점에 부분 매도 -> 양의 실현손익 발생.
		seedCryptoPrice(realInstrument, new BigDecimal("50000000"));
		OrderResponse realBuy = performOrderCall(accessToken, buyRequest(realInstrument.getId(), REAL_BUY_QUANTITY));
		seedCryptoPrice(realInstrument, new BigDecimal("80000000"));
		OrderResponse realSell = performOrderCall(accessToken, sellRequest(realInstrument.getId(), REAL_SELL_QUANTITY));
		assertThat(realSell.realizedPnl()).isNotNull();

		// 샌드박스 종목: 시세 인프라 없이 매수 후 부분 매도(TutorialSampleInstrumentPriceService가 항상
		// AVAILABLE 가격을 낸다). realizedPnl 부호·크기는 사인파 가격에 좌우돼 예측하지 않는다 — account
		// 집계에 반영되지 않는다는 것만 확인한다.
		OrderResponse sandboxBuy = performOrderCall(
			accessToken, buyRequest(sandboxInstrument.getId(), SANDBOX_BUY_QUANTITY));
		OrderResponse sandboxSell = performOrderCall(
			accessToken, sellRequest(sandboxInstrument.getId(), SANDBOX_SELL_QUANTITY));

		// 매수·매도 회고를 실제 종목·샌드박스 종목 양쪽에 모두 남긴다.
		BuyJournalResponse realBuyJournal = journalService.createBuyJournal(
			user.getId(), realBuy.tradeId(), "실제 종목 매수 회고");
		SellJournalResponse realSellJournal = journalService.createSellJournal(
			user.getId(), realSell.tradeId(), "실제 종목 매도 회고");
		journalService.createBuyJournal(user.getId(), sandboxBuy.tradeId(), "샌드박스 매수 회고");
		journalService.createSellJournal(user.getId(), sandboxSell.tradeId(), "샌드박스 매도 회고");

		// (1) SANDBOX-EXCL-001: GET /api/holdings — 실제 종목 보유만 남는다.
		mockMvc.perform(get("/api/holdings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].instrumentId").value(realInstrument.getId()));

		// (2) SANDBOX-EXCL-002: GET /api/journal — 실제 종목 체결의 회고 2건만 남는다.
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

		// (3) SANDBOX-EXCL-003·004: GET /api/rankings — realizedPnl은 실제 종목 매도분만 반영한다
		// (샌드박스 매도의 실현손익은 account.realizedPnl에 더해지지 않았으므로 합산값과 다르다).
		RankingListResponse rankings = getRankings(accessToken);
		assertThat(rankings.content()).hasSize(1);
		RankingListItemResponse item = rankings.content().get(0);
		assertThat(item.nickname()).isEqualTo(user.getNickname());
		assertThat(item.realizedPnl()).isEqualTo(realSell.realizedPnl());

		// (4) SANDBOX-EXCL-003: GET /api/rankings/me — 목록과 동일한 realizedPnl, 매도 이력 있음으로 판정.
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

	// 기존 시드 코인(V7)을 재사용한다 — 새 Instrument를 커밋하면 InstrumentRepositoryTest의 절대개수 단정을
	// 깨뜨릴 위험이 있다(RankingIntegrationTest와 동일 근거, 이 클래스도 @Transactional로 롤백할 수 없다).
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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, LocalDateTime.now()));
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
