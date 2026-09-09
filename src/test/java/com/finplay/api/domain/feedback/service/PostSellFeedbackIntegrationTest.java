package com.finplay.api.domain.feedback.service;

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
import com.finplay.api.domain.journal.repository.SellTradeJournalRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@Import(TestcontainersConfiguration.class)
class PostSellFeedbackIntegrationTest {

	private static final String PATH = "/api/ai/post-sell/{tradeId}";

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 8, 5);
	private static final LocalDate EARLIER_SERVICE_DATE = LocalDate.of(2031, 8, 3);
	private static final LocalDate MIDDLE_SERVICE_DATE = LocalDate.of(2031, 8, 4);

	private static final LocalTime EARLIEST_BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime LATER_BUY_TIME = LocalTime.of(10, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private static final LocalDateTime NOW = LocalDateTime.of(SERVICE_DATE, SELL_TIME);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

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
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private SellTradeJournalRepository sellTradeJournalRepository;

	private User owner;
	private Account account;
	private Instrument stock;
	private Holding holding;
	private String accessToken;

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("post-sell-owner@finplay.com", "hash", "owner208", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, NOW));
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208I", "테스트종목208I", BigDecimal.valueOf(100), 10_000L, true, NOW));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, NOW));
		accessToken = jwtTokenProvider.issue(owner.getId(), owner.getRole()).accessToken();
	}

	@Test
	@DisplayName("투자일기 없이 매수·매도한 두 lot 배분 건도 200이고 수치가 계약 예시대로 나온다")
	void returnsLedgerSummaryForTwoLotSellWithoutAnyJournal() throws Exception {
		StockReplaySession session = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		Trade sellTrade = saveSellTrade(session);
		HoldingLot earliest = saveLot(session, EARLIEST_BUY_TIME, new BigDecimal("4"));
		HoldingLot later = saveLot(session, LATER_BUY_TIME, new BigDecimal("6"));
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isFalse();

		String body = mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeId").value(sellTrade.getId()))
			.andExpect(jsonPath("$.symbol").value("TEST208I"))
			.andExpect(jsonPath("$.buyAt").value("2026-07-29T09:30:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-07-29T14:40:00"))
			.andExpect(jsonPath("$.fee").value(102))
			.andExpect(jsonPath("$.realizedPnl").value(-15207))
			.andExpect(jsonPath("$.holdingMinutes").value(310))
			.andExpect(jsonPath("$.sameSessionCompleted").value(true))
			.andExpect(jsonPath("$.priceMoves.length()").value(0))
			.andExpect(jsonPath("$.postSellFlow.status").isNotEmpty())
			.andExpect(jsonPath("$.counterfactuals.status").isNotEmpty())
			.andExpect(jsonPath("$.peerComparison.status").value("NO_EVENT"))
			.andExpect(jsonPath("$.narrative").isNotEmpty())
			.andExpect(jsonPath("$.narrativeSource").value("TEMPLATE"))
			.andExpect(jsonPath("$.narrativeStatus").value("READY"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"buyPrice\":70000.00000000");
		assertThat(body).contains("\"returnRate\":-0.0217");
	}

	@Test
	@DisplayName("나중 lot의 원본 거래일이 다르면 sameSessionCompleted=false다")
	void returnsSameSessionCompletedFalseWhenOnlyALaterLotIsFromAnotherOriginTradeDate() throws Exception {
		StockReplaySession sellSession = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		StockReplaySession earliestLotSession = saveSession(EARLIER_SERVICE_DATE, ORIGIN_TRADE_DATE);
		StockReplaySession otherDateSession = saveSession(MIDDLE_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		Trade sellTrade = saveSellTrade(sellSession);
		HoldingLot earliest = saveLot(earliestLotSession, EARLIEST_BUY_TIME, new BigDecimal("4"));
		HoldingLot later = saveLot(otherDateSession, LATER_BUY_TIME, new BigDecimal("6"));
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);

		assertThat(earliest.getBuyTrade().getStockReplaySession().getSourceTradingDate())
			.isEqualTo(sellSession.getSourceTradingDate());
		assertThat(later.getBuyTrade().getStockReplaySession().getSourceTradingDate())
			.isNotEqualTo(sellSession.getSourceTradingDate());

		mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sameSessionCompleted").value(false))
			.andExpect(jsonPath("$.buyAt").value("2026-07-29T09:30:00"))
			.andExpect(jsonPath("$.priceMoves.length()").value(0));
	}

	@Test
	@DisplayName("없는 tradeId는 404다")
	void returnsNotFoundForUnknownTradeId() throws Exception {
		mockMvc.perform(authorized(get(PATH, 99_999_999L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("타인 체결은 403이다 — 배분을 읽기 전에 끝난다")
	void returnsForbiddenForAnotherUsersTrade() throws Exception {
		User stranger = userRepository.saveAndFlush(
			User.create("post-sell-stranger@finplay.com", "hash", "stranger208", NOW));
		Account strangerAccount = accountRepository.saveAndFlush(
			Account.create(stranger, Market.STOCK, NOW));
		StockReplaySession session = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		Trade strangerSell = saveTrade(
			strangerAccount, stock, session, OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"),
			-15_207L, LocalDateTime.of(SERVICE_DATE, SELL_TIME));

		mockMvc.perform(authorized(get(PATH, strangerSell.getId())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("매수 체결은 400이다")
	void returnsBadRequestForBuyTrade() throws Exception {
		StockReplaySession session = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		Trade buyTrade = saveTrade(
			account, stock, session, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"),
			null, LocalDateTime.of(SERVICE_DATE, EARLIEST_BUY_TIME));

		mockMvc.perform(authorized(get(PATH, buyTrade.getId())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}


	@Test
	@DisplayName("토큰 없이 호출하면 401이다")
	void returnsUnauthorizedWithoutToken() throws Exception {
		mockMvc.perform(get(PATH, 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}


	private StockReplaySession saveSession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt));
	}

	private Trade saveSellTrade(StockReplaySession session) {
		return saveTrade(
			account, stock, session, OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"),
			-15_207L, LocalDateTime.of(session.getServiceDate(), SELL_TIME));
	}

	private HoldingLot saveLot(StockReplaySession session, LocalTime executedTime, BigDecimal quantity) {
		LocalDateTime executedAt = LocalDateTime.of(session.getServiceDate(), executedTime);
		Trade buyTrade = saveTrade(
			account, stock, session, OrderSide.BUY, quantity, new BigDecimal("70000"), null, executedAt);
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, new BigDecimal("70000"), 100L, executedAt, executedAt));
	}

	private Trade saveTrade(
		Account tradeAccount,
		Instrument instrument,
		StockReplaySession session,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			tradeAccount.getUser(), tradeAccount, instrument, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, tradeAccount, instrument, session, side, price, quantity,
			price.multiply(quantity).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}

	private void saveAllocation(
		Trade sellTrade, HoldingLot lot, BigDecimal quantity, long allocatedCost, long allocatedBuyFee) {
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, quantity, allocatedCost, allocatedBuyFee, NOW));
	}

	private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}
}
