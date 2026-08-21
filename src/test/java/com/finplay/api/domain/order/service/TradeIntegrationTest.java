// 매수·매도 파이프라인(이슈 #13·#41)으로 생성한 실제 원장 데이터를 GET /api/trades로 검증하는 통합 테스트다.
package com.finplay.api.domain.order.service;

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
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

// 이 테스트도 HoldingIntegrationTest(이슈 #52)·AccountSummaryIntegrationTest(이슈 #81, agent-mistakes.md 2026-07-30 항목)와
// 동일하게 instruments·stock_replay_sessions 테이블에 saveAndFlush로 실제 커밋을 남기는 조합이므로, `./gradlew build` 전체
// 실행에서 InstrumentRepositoryTest 등의 절대개수 단정을 깨뜨리지 않도록 `@Transactional`로 각 테스트 종료 시 롤백시킨다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class TradeIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);
	private static final LocalTime THIRD_CANDLE_TIME = LocalTime.of(10, 1);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

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
	private JwtTokenProvider jwtTokenProvider;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void buyTradesExposeNullPnlAndSellTradeRealizedPnlMatchesLedger() throws Exception {
		User user = createUser("trd-fifo");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument instrument = createStockInstrument("TFIFO");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		createCandle(instrument, THIRD_CANDLE_TIME, new BigDecimal("100000"));

		// 매수1: 10:00 시각 → 09:59 분봉(60000) 체결가, 매수2: 10:01 시각 → 10:00 분봉(80000) 체결가.
		OrderResponse buy1 = orderService.createOrder(user.getId(), "trd-buy-1", buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		OrderResponse buy2 = orderService.createOrder(user.getId(), "trd-buy-2", buyRequest(instrument.getId(), "10"));

		// 매도: 10:02 시각 → 10:01 분봉(100000) 체결가, 5주만 매도(FIFO로 매수1 lot 일부 소진).
		clock.set(BASE_NOW.plusMinutes(2));
		OrderResponse sell = orderService.createOrder(user.getId(), "trd-sell-1", sellRequest(instrument.getId(), "5"));
		assertThat(sell.realizedPnl()).isNotNull();

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("limit", "10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(3))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			// 최신순: 매도(10:02) → 매수2(10:01) → 매수1(10:00).
			.andExpect(jsonPath("$.content[0].tradeId").value(sell.tradeId()))
			.andExpect(jsonPath("$.content[0].side").value("SELL"))
			.andExpect(jsonPath("$.content[0].quantity").value(5))
			.andExpect(jsonPath("$.content[0].amount").value(sell.amount()))
			.andExpect(jsonPath("$.content[0].fee").value(sell.fee()))
			.andExpect(jsonPath("$.content[0].realizedPnl").value(sell.realizedPnl()))
			.andExpect(jsonPath("$.content[1].tradeId").value(buy2.tradeId()))
			.andExpect(jsonPath("$.content[1].side").value("BUY"))
			.andExpect(jsonPath("$.content[1].realizedPnl").doesNotExist())
			.andExpect(jsonPath("$.content[2].tradeId").value(buy1.tradeId()))
			.andExpect(jsonPath("$.content[2].side").value("BUY"))
			.andExpect(jsonPath("$.content[2].realizedPnl").doesNotExist());
	}

	@Test
	void cursorPaginationAcrossPagesMatchesSinglePageFetchInSetAndOrder() throws Exception {
		User user = createUser("trd-page");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		Instrument instrument = createStockInstrument("TPAGE");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));

		// 5건의 매수를 서로 다른 시각(분 단위 전진)에 체결시켜 executedAt이 모두 달라지게 한다.
		for (int i = 1; i <= 5; i++) {
			clock.set(BASE_NOW.plusMinutes(i));
			orderService.createOrder(user.getId(), "trd-page-buy-" + i,
				buyRequest(instrument.getId(), String.valueOf(i)));
		}

		List<Long> pagedIds = collectAllTradeIdsByCursor(accessToken, "STOCK", 2);
		List<Long> singleCallIds = collectSinglePageTradeIds(accessToken, "STOCK", 100);

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(singleCallIds);
	}

	@Test
	void otherAccountsTradesDoNotLeakIntoOwnList() throws Exception {
		User owner = createUser("trd-lk-owner");
		createAccount(owner);
		String ownerAccessToken = issueAccessToken(owner);
		Instrument ownerInstrument = createStockInstrument("TLKOWN");
		createCandle(ownerInstrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		OrderResponse ownerBuy = orderService.createOrder(
			owner.getId(), "trd-lk-owner-buy", buyRequest(ownerInstrument.getId(), "3"));

		User other = createUser("trd-lk-other");
		createAccount(other);
		Instrument otherInstrument = createStockInstrument("TLKOTH");
		createCandle(otherInstrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(other.getId(), "trd-lk-other-buy", buyRequest(otherInstrument.getId(), "4"));

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerAccessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].tradeId").value(ownerBuy.tradeId()))
			.andExpect(jsonPath("$.content[0].instrumentId").value(ownerInstrument.getId()));
	}

	@Test
	void missingMarketAndCorruptedCursorReturn400AndUnauthenticatedReturns401() throws Exception {
		User user = createUser("trd-invalid");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/trades")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("cursor", "garbage")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/trades").param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void newAccountWithNoTradesReturnsEmptyPage() throws Exception {
		User user = createUser("trd-empty");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	// limit보다 데이터가 많을 때 nextCursor를 따라 끝까지 페이지를 넘기며 tradeId를 최신순 그대로 수집한다.
	private List<Long> collectAllTradeIdsByCursor(String accessToken, String market, int limit) throws Exception {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20); // 무한루프 방지 안전장치.

			MockHttpServletRequestBuilder request = get("/api/trades")
				.param("market", market)
				.param("limit", String.valueOf(limit))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}

			String body = mockMvc.perform(request)
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			JsonNode root = objectMapper.readTree(body);
			root.get("content").forEach(node -> ids.add(node.get("tradeId").asLong()));
			hasNext = root.get("hasNext").asBoolean();
			cursor = hasNext ? root.get("nextCursor").asText() : null;
		}
		return ids;
	}

	// 커서 없이 한 번에 큰 limit으로 조회해 전체 tradeId를 최신순 그대로 수집한다(페이지 결과와 비교하는 기준선).
	private List<Long> collectSinglePageTradeIds(String accessToken, String market, int limit) throws Exception {
		String body = mockMvc.perform(get("/api/trades")
			.param("market", market)
			.param("limit", String.valueOf(limit))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode root = objectMapper.readTree(body);
		List<Long> ids = new ArrayList<>();
		root.get("content").forEach(node -> ids.add(node.get("tradeId").asLong()));
		return ids;
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, BASE_NOW));
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
