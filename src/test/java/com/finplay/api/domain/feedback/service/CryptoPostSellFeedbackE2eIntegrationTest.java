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
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.market.service.FakeCryptoCandleProvider;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, CryptoPostSellFeedbackE2eIntegrationTest.CryptoE2eTestConfig.class})
class CryptoPostSellFeedbackE2eIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String PATH = "/api/ai/post-sell/{tradeId}";

	private static final String SYMBOL = "E2E275";

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDateTime BUY_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(12, 0));

	private static final LocalDateTime CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(10, 30));

	static final LocalDateTime VIEW_AT = LocalDateTime.of(SELL_DATE.plusDays(1), LocalTime.of(9, 0));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal BUY_PRICE = new BigDecimal("70000");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private static final long ALLOCATED_COST = 700_000L;
	private static final long ALLOCATED_BUY_FEE = 105L;
	private static final long SELL_FEE = 342L;
	private static final long REALIZED_PNL = -15_447L;

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	private static final List<String> OTHER_FEEDBACK_TABLES = List.of("price_move_events",
		"price_move_event_sources", "instrument_news_summaries", "market_briefings", "price_move_peer_stats");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@Autowired
	private FakeCryptoCandleProvider cryptoCandleProvider;

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
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Instrument coin;
	private Trade sellTrade;
	private String accessToken;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();
		cryptoCandleProvider.reset();
		cryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_DAY, List.of(candle(
			SELL_DATE.atStartOfDay(), "69200")));
		cryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_MINUTE, List.of(
			candle(BUY_AT, "70000"),
			candle(CARD_AT, "70800"),
			candle(SELL_AT, "68500")));

		owner = userRepository.saveAndFlush(User.create("crypto-e2e@finplay.com", "hash", "ce2e", BUY_AT));
		coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "E2E테스트코인", BigDecimal.valueOf(1), 5_000L, true, BUY_AT));
		sellTrade = givenOwnCryptoSellTrade();
		accessToken = jwtTokenProvider.issue(owner.getId(), owner.getRole()).accessToken();
	}


	@Test
	@DisplayName("코인 매도 체결 조회가 400이 아니라 200이고 원장 수치가 채워진다")
	void returnsOkWithLedgerNumbersForACryptoSellTrade() throws Exception {
		fakeNarrativeGenerator.enqueueFailure();

		mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeId").value(sellTrade.getId()))
			.andExpect(jsonPath("$.symbol").value(SYMBOL))
			.andExpect(jsonPath("$.buyPrice").value(70000.00000000))
			.andExpect(jsonPath("$.sellPrice").value(68500))
			.andExpect(jsonPath("$.quantity").value(10))
			.andExpect(jsonPath("$.fee").value(SELL_FEE))
			.andExpect(jsonPath("$.realizedPnl").value(REALIZED_PNL))
			.andExpect(jsonPath("$.returnRate").value(-0.0221))
			.andExpect(jsonPath("$.holdingMinutes").value(180))
			.andExpect(jsonPath("$.sameSessionCompleted").value(true))
			.andExpect(jsonPath("$.buyAt").value("2026-08-05T09:00:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-08-05T12:00:00"))
			.andExpect(jsonPath("$.holdHighBasis").value("MINUTE"))
			.andExpect(jsonPath("$.holdHighPrice").value(70800));
	}


	@Test
	@DisplayName("보유 구간의 코인 변동 카드가 노출 게이트 없이 priceMoves에 들어온다")
	void includesHeldCryptoPriceMoveWithoutARevealGate() throws Exception {
		fakeNarrativeGenerator.enqueueFailure();
		PriceMoveEvent card = givenHeldCryptoCard();

		mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.priceMoves.length()").value(1))
			.andExpect(jsonPath("$.priceMoves[0].id").value(card.getId()))
			.andExpect(jsonPath("$.priceMoves[0].windowEnd").value("2026-08-05T10:30:00"))
			.andExpect(jsonPath("$.priceMoves[0].minutesAfterBuy").value(90))
			.andExpect(jsonPath("$.priceMoves[0].minutesBeforeSell").value(90))
			.andExpect(jsonPath("$.priceMoves[0].sources.length()").value(1))
			.andExpect(jsonPath("$.priceMoves[0].sources[0].title").value("대형 거래소 상장 소식"));
	}


	@Test
	@DisplayName("코인 체결도 LLM 실패 시 템플릿 문장으로 대체되고 narrativeStatus는 READY·source는 TEMPLATE이다")
	void fallsBackToTheTemplateSentenceForCryptoTrades() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(
			owner.getId(), sellTrade.getId());

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrative()).isNotBlank();
	}


	@Test
	@DisplayName("코인 회고 조회는 trade_feedbacks에만 1행을 쓰고 원장·읽기 전용·다른 피드백 테이블은 그대로다")
	void neverWritesOutsideTradeFeedbacks() {
		fakeNarrativeGenerator.enqueueFailure();
		givenHeldCryptoCard();

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		Map<String, Object> tradeRowBefore = tradeRow();
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long feedbacksBefore = feedbackRowCount();

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(
			owner.getId(), sellTrade.getId());

		assertThat(response.narrative()).isNotBlank();
		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		assertThat(tradeRow()).isEqualTo(tradeRowBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}


	private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}

	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new CryptoCandleDto(
			sourceTime, price, price.add(new BigDecimal("500")), price.subtract(new BigDecimal("500")),
			price, new BigDecimal("1.5"));
	}

	private PriceMoveEvent givenHeldCryptoCard() {
		PriceMoveEvent card = priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			coin, CARD_AT, new BigDecimal("0.021000"), new BigDecimal("3.2500"),
			"10시 30분부터 2.1% 상승했습니다.", NarrativeSource.TEMPLATE, CARD_AT));
		MarketNewsItem news = marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			coin, MarketNewsItemType.NEWS, "대형 거래소 상장 소식", "coindesk.com",
			"https://news.example.test/crypto/1", CARD_AT.minusMinutes(5), CARD_AT));
		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(card, news));
		return card;
	}

	private Trade givenOwnCryptoSellTrade() {
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, Market.CRYPTO, BUY_AT));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, coin, BUY_AT));

		Trade buyTrade = saveTrade(account, OrderSide.BUY, BUY_PRICE, ALLOCATED_COST, ALLOCATED_BUY_FEE, null, BUY_AT);
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, QUANTITY, BUY_PRICE, ALLOCATED_BUY_FEE, BUY_AT, BUY_AT));

		Trade sell = saveTrade(account, OrderSide.SELL, SELL_PRICE, 685_000L, SELL_FEE, REALIZED_PNL, SELL_AT);
		lot.consume(QUANTITY);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sell, lot, QUANTITY, ALLOCATED_COST, ALLOCATED_BUY_FEE, SELL_AT));
		return sell;
	}

	private Trade saveTrade(
		Account account, OrderSide side, BigDecimal price, long amount, long fee, Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, coin, side, OrderType.MARKET, QUANTITY,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, coin, null, side, price, QUANTITY, amount, fee, realizedPnl, executedAt, executedAt));
	}

	private long feedbackRowCount() {
		entityManager.flush();
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM trade_feedbacks WHERE trade_id = ?", Long.class, sellTrade.getId());
	}

	private Map<String, Object> tradeRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap("SELECT * FROM trades WHERE id = ?", sellTrade.getId());
	}

	private List<Map<String, Object>> mutableLedgerValues() {
		entityManager.flush();
		List<Map<String, Object>> rows = new ArrayList<>(
			jdbcTemplate.queryForList("SELECT id, cash_balance FROM accounts ORDER BY id"));
		rows.addAll(jdbcTemplate.queryForList("SELECT id, remaining_quantity FROM holding_lots ORDER BY id"));
		return rows;
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	@TestConfiguration
	static class CryptoE2eTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
