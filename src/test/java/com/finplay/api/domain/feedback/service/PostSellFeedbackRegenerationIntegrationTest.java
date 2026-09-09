package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	PostSellFeedbackRegenerationIntegrationTest.RegenerationTestConfig.class})
class PostSellFeedbackRegenerationIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);
	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	private static final String FIRST_NARRATIVE = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
	private static final String REGENERATED_NARRATIVE = "마감 종가는 69,200원으로 매도가보다 1.02% 높습니다.";

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@Autowired
	private FeedbackLlmProperties feedbackLlmProperties;

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
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Account account;
	private Instrument stock;
	private StockReplaySession tradeSession;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository.saveAndFlush(User.create("post-sell-regen@finplay.com", "hash", "regen208", VIEW_AT));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, VIEW_AT));
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208R", "테스트종목208R", BigDecimal.valueOf(100), 10_000L, true,
				VIEW_AT));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		tradeSession = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));

		saveCandle(BUY_TIME, "69500");
		saveCandle(LocalTime.of(11, 5), "70800");
		saveCandle(SELL_TIME, "68500");
		saveCandle(LocalTime.of(15, 5), "69500");
		saveCandle(LAST_CANDLE_TIME, "69200");

		LocalDateTime buyExecutedAt = LocalDateTime.of(TRADE_SERVICE_DATE, BUY_TIME);
		Trade buyTrade = saveTrade(OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"), null, buyExecutedAt);
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L, buyExecutedAt, VIEW_AT));

		LocalDateTime sellExecutedAt = LocalDateTime.of(TRADE_SERVICE_DATE, SELL_TIME);
		sellTrade = saveTrade(
			OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"), -15_207L, sellExecutedAt);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, new BigDecimal("10"), 700_000L, 105L, VIEW_AT));
	}

	@Test
	@DisplayName("카드 0건(NO_EVENT)이어도 게이트 통과 후 첫 조회에서 재생성되고 두 번째 조회에서는 재생성되지 않는다")
	void regeneratesOnceAfterTheGateOpensEvenWithoutAnyCard() {
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		PostSellFeedbackResponse created = getPostSellFeedback();
		assertThat(created.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(created.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(feedbackRow()).containsEntry("narrative_finalized", false);

		PostSellFeedbackResponse regenerated = getPostSellFeedback();

		assertThat(regenerated.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(regenerated.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(regenerated.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(feedbackRow())
			.containsEntry("narrative", REGENERATED_NARRATIVE)
			.containsEntry("narrative_finalized", true)
			.containsEntry("regeneration_attempts", 1);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);

		PostSellFeedbackResponse reused = getPostSellFeedback();

		assertThat(reused.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
		assertThat(feedbackRow()).containsEntry("regeneration_attempts", 1);
	}

	@Test
	@DisplayName("집단 비교가 INSUFFICIENT_SAMPLE이어도 확정으로 쳐서 재생성한다")
	void regeneratesWhenThePeerSampleIsInsufficient() {
		PriceMoveEvent card = saveCard(LocalTime.of(9, 45), LocalTime.of(9, 50));
		savePeerStat(card, 4);
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		PostSellFeedbackResponse created = getPostSellFeedback();
		assertThat(created.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.INSUFFICIENT_SAMPLE);
		PostSellFeedbackResponse regenerated = getPostSellFeedback();

		assertThat(regenerated.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(feedbackRow()).containsEntry("narrative_finalized", true);
	}

	@Test
	@DisplayName("재생성 프롬프트에 매도 후 흐름 줄이 실제로 들어간다")
	void putsThePostSellFlowLineIntoTheRegenerationPrompt() {
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		getPostSellFeedback();
		getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.userPrompts()).hasSize(2);
		assertThat(fakeNarrativeGenerator.userPrompts().get(1))
			.contains("매도 후 흐름")
			.contains("69,200원");
	}

	@Test
	@DisplayName("재생성 실패가 누적 상한을 넘지 않고 기존 서술과 narrative_finalized=false가 유지된다")
	void neverExceedsTheCumulativeRetryLimit() {
		int limit = feedbackLlmProperties.maxNarrativeRetry();
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE);

		getPostSellFeedback();
		assertThat(feedbackRow()).containsEntry("regeneration_attempts", 0);

		for (int attempt = 1; attempt <= limit; attempt++) {
			PostSellFeedbackResponse response = getPostSellFeedback();

			assertThat(response.narrative()).as("실패해도 기존 서술이 유지된다").isEqualTo(FIRST_NARRATIVE);
			assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
			assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
			assertThat(feedbackRow())
				.containsEntry("regeneration_attempts", attempt)
				.containsEntry("narrative_finalized", false)
				.containsEntry("narrative", FIRST_NARRATIVE);
			assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(attempt + 1);
		}

		int callsAtLimit = fakeNarrativeGenerator.callCount();
		PostSellFeedbackResponse afterLimit = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(callsAtLimit);
		assertThat(afterLimit.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(afterLimit.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(feedbackRow())
			.containsEntry("regeneration_attempts", limit)
			.containsEntry("narrative_finalized", false);
	}

	@Test
	@DisplayName("집단 비교가 NOT_YET이면 재생성하지 않고 최초 서술을 그대로 재사용한다")
	void neverRegeneratesWhilePeerComparisonIsNotYet() {
		saveCard(LocalTime.of(9, 45), LocalTime.of(9, 50));
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(second.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(feedbackRow())
			.containsEntry("regeneration_attempts", 0)
			.containsEntry("narrative_finalized", false);
	}

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private Map<String, Object> feedbackRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap(
			"SELECT narrative, narrative_source, narrative_finalized, regeneration_attempts, generated_at "
				+ "FROM trade_feedbacks WHERE trade_id = ?",
			sellTrade.getId());
	}

	private PriceMoveEvent saveCard(LocalTime windowStart, LocalTime windowEnd) {
		return priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			"테스트 카드",
			NarrativeSource.LLM,
			windowEnd.plusMinutes(1),
			VIEW_AT));
	}

	private void savePeerStat(PriceMoveEvent card, int holderCount) {
		priceMovePeerStatRepository.saveAndFlush(PriceMovePeerStat.create(
			card, TRADE_SERVICE_DATE, holderCount, 1, 15, VIEW_AT));
	}

	private void saveCandle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", VIEW_AT));
	}

	private Trade saveTrade(
		OrderSide side, BigDecimal quantity, BigDecimal price, Long realizedPnl, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, tradeSession, side, price, quantity,
			price.multiply(quantity).longValueExact(), side == OrderSide.BUY ? 105L : 102L, realizedPnl, executedAt,
			executedAt));
	}

	@TestConfiguration
	static class RegenerationTestConfig {

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
