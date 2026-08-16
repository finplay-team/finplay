// 매수 파이프라인(이슈 #13)으로 생성한 실제 원장·시세 데이터로 GET /api/portfolio가 GET /api/accounts/summary의
// 시장별 합산과 정확히 일치하는지 검증하는 통합 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

// 이 테스트도 HoldingIntegrationTest(#52)·TradeIntegrationTest(#82)·AccountSummaryIntegrationTest(#81,
// agent-mistakes.md 2026-07-30 항목)와 동일하게 instruments·stock_replay_sessions 테이블에 saveAndFlush로 실제
// 커밋을 남기는 조합이므로, `./gradlew build` 전체 실행에서 다른 슬라이스 테스트의 절대개수 단정을 깨뜨리지 않도록
// `@Transactional`로 각 테스트 종료 시 롤백시킨다. MockMvc 호출은 테스트 메서드와 같은 스레드에서 동기 실행되어 같은
// 트랜잭션에 참여하므로 합산 일치 검증 자체는 약화되지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PortfolioSummaryIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);
	// Account.INITIAL_SEED_MONEY(10,000,000)는 STOCK·CRYPTO 계좌 생성 시 항상 고정값이라 두 계좌 합계를
	// 상수로 둔다 — AccountSummaryResponse가 seedMoney를 노출하지 않으므로(#81 계약) 응답에서 얻을 수 없다.
	private static final long TOTAL_SEED_MONEY = 20_000_000L;
	private static final int RETURN_RATE_SCALE = 8; // PortfolioService.RETURN_RATE_SCALE과 동일(이슈 #390)

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private TestClock clock;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	private final ObjectMapper objectMapper = new ObjectMapper();

	// AccountSummaryIntegrationTest(PR #96 리뷰 권장사항 2)와 동일하게, Redis(feed:crypto:status·price:crypto:*)는
	// `@Transactional`(JPA) 롤백 대상이 아니라 실제로 남으므로 이 테스트가 쓴 코인 시세 키만 추적했다가 지운다.
	private String cryptoPriceKeyToCleanUp;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		if (cryptoPriceKeyToCleanUp != null) {
			redisTemplate.delete(cryptoPriceKeyToCleanUp);
			cryptoPriceKeyToCleanUp = null;
		}
	}

	@Test
	void emptyAccountImmediatelyAfterSignupReturnsSummedSeedMoneyWithZeroPnl() throws Exception {
		User user = createUser("pf-signup");
		accountService.createAccountsFor(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(authorized(get("/api/portfolio"), accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalValue").value(TOTAL_SEED_MONEY))
			.andExpect(jsonPath("$.unrealizedPnl").value(0))
			.andExpect(jsonPath("$.realizedPnl").value(0))
			.andExpect(jsonPath("$.returnRate").value(0.0));
	}

	@Test
	void stockOnlyBuyMatchesExactSumOfBothMarketSummaries() throws Exception {
		User user = createUser("pf-stock");
		accountService.createAccountsFor(user);
		String accessToken = issueAccessToken(user);

		Instrument instrument = createStockInstrument("PFSTK");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		// 10:00 시각 → 09:59 분봉(70000)이 체결가.
		orderService.createOrder(user.getId(), "pf-stock-buy-idem", buyRequest(instrument.getId(), "10"));

		// 10:01로 시각 이동 → 10:00 분봉(80000)이 최신 시세가 되어 평가에 반영된다. CRYPTO 계좌는 그대로 둔다.
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		clock.set(BASE_NOW.plusMinutes(1));

		assertPortfolioMatchesSummedAccountSummaries(accessToken);
	}

	@Test
	void bothMarketsBuyMatchesExactSumOfBothMarketSummaries() throws Exception {
		User user = createUser("pf-both");
		accountService.createAccountsFor(user);
		String accessToken = issueAccessToken(user);

		Instrument stock = createStockInstrument("PFBOTHS");
		createCandle(stock, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(user.getId(), "pf-both-stock-idem", buyRequest(stock.getId(), "10"));
		createCandle(stock, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		clock.set(BASE_NOW.plusMinutes(1));

		// PriceStore.isStale은 clock 기준 수신시각 10초 초과를 stale로 판정한다 — 이미 BASE_NOW+1분으로 전진시킨
		// 현재 clock과 같은 시각으로 tick을 저장해야 매수 시점에 시세가 유효(AVAILABLE)로 조회된다.
		String cryptoSymbol = "PFBOTHC" + UUID.randomUUID().toString().substring(0, 6);
		Instrument crypto = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, cryptoSymbol, "포트폴리오코인", BigDecimal.ONE, 0L, true, BASE_NOW));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(cryptoSymbol, new BigDecimal("1000000"), BASE_NOW.plusMinutes(1));
		cryptoPriceKeyToCleanUp = "price:crypto:" + cryptoSymbol;
		orderService.createOrder(user.getId(), "pf-both-crypto-idem",
			new OrderCreateRequest(Market.CRYPTO, crypto.getId(), OrderSide.BUY, "MARKET", new BigDecimal("1")));

		assertPortfolioMatchesSummedAccountSummaries(accessToken);
	}

	@Test
	void otherUsersHoldingsDoNotLeakIntoOwnPortfolio() throws Exception {
		User owner = createUser("pf-owner");
		accountService.createAccountsFor(owner);
		String ownerAccessToken = issueAccessToken(owner);

		User other = createUser("pf-other");
		accountService.createAccountsFor(other);
		Instrument instrument = createStockInstrument("PFOTHR");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(other.getId(), "pf-other-buy-idem", buyRequest(instrument.getId(), "10"));

		mockMvc.perform(authorized(get("/api/portfolio"), ownerAccessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalValue").value(TOTAL_SEED_MONEY))
			.andExpect(jsonPath("$.unrealizedPnl").value(0))
			.andExpect(jsonPath("$.realizedPnl").value(0));
	}

	@Test
	void unauthenticatedReturns401() throws Exception {
		mockMvc.perform(get("/api/portfolio"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	// spec 요구사항: 수동으로 4개 값을 다시 계산하지 않고, 실제 GET /api/accounts/summary(STOCK·CRYPTO) 두 응답을
	// 합산한 값과 GET /api/portfolio 응답을 대조한다. returnRate만 예외로, "시장별 수익률을 더하거나 평균 내지
	// 않는다"(이슈 #51 요구사항)는 계약이라 PortfolioService가 실제로 쓰는 것과 동일한 공식(합산된 totalValue 기준
	// 재계산)으로 검증한다.
	private void assertPortfolioMatchesSummedAccountSummaries(String accessToken) throws Exception {
		JsonNode stockSummary = fetchJson(get("/api/accounts/summary").param("market", "STOCK"), accessToken);
		JsonNode cryptoSummary = fetchJson(get("/api/accounts/summary").param("market", "CRYPTO"), accessToken);
		JsonNode portfolio = fetchJson(get("/api/portfolio"), accessToken);

		long expectedTotalValue = stockSummary.get("totalValue").asLong() + cryptoSummary.get("totalValue").asLong();
		long expectedUnrealizedPnl = stockSummary.get("unrealizedPnl").asLong()
			+ cryptoSummary.get("unrealizedPnl").asLong();
		long expectedRealizedPnl = stockSummary.get("realizedPnl").asLong() + cryptoSummary.get("realizedPnl").asLong();
		BigDecimal expectedReturnRate = BigDecimal.valueOf(expectedTotalValue - TOTAL_SEED_MONEY)
			.divide(BigDecimal.valueOf(TOTAL_SEED_MONEY), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		assertThat(portfolio.get("totalValue").asLong()).isEqualTo(expectedTotalValue);
		assertThat(portfolio.get("unrealizedPnl").asLong()).isEqualTo(expectedUnrealizedPnl);
		assertThat(portfolio.get("realizedPnl").asLong()).isEqualTo(expectedRealizedPnl);
		assertThat(portfolio.get("returnRate").decimalValue()).isEqualByComparingTo(expectedReturnRate);
	}

	private JsonNode fetchJson(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
		String body = mockMvc.perform(authorized(request, accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body);
	}

	private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String accessToken) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

}
