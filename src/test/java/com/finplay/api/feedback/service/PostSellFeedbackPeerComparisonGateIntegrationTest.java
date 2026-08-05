// 고정 Clock + 대역 생성기 + 실 MySQL로 4번 항목의 peerComparison 실제 판정이 서술 재생성 게이트를 여는지 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * tasks.md 012 이슈 #212 4번 항목 — "재생성 게이트가 이 항목으로 실제로 열리는지"를 확인한다. 게이트 조건
 * ({@code postSellFlow.status == READY && peerComparison.status != NOT_YET}, {@code PostSellFeedbackService})
 * 자체는 이 이슈가 손대지 않는다 — {@code peerComparison.status}가 상수 {@code NOT_YET}에서 4번 항목의 실제
 * 판정으로 바뀌는 순간 코드 수정 없이 열린다는 것이 검증 대상이다.
 *
 * <p>누적 상한·중복 방지 등 게이트 종단의 나머지 시나리오는 5번 항목이 실제 픽스처로 전환한
 * {@code PostSellFeedbackRegenerationIntegrationTest}가 맡는다. 이 파일은 "4번 항목이 이 게이트를 실제로
 * 열었다"는 사실 하나만 최소로 남긴다.
 */
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	PostSellFeedbackPeerComparisonGateIntegrationTest.GateTestConfig.class})
class PostSellFeedbackPeerComparisonGateIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	// PostSellFeedbackGateIntegrationTest 등과 같은 이유로 이 파일 전용 연도(2032)를 쓴다 — 공유 Testcontainer에
	// 트랜잭션 없이 커밋하는 다른 테스트와 서비스 날짜(UNIQUE)가 겹치지 않게 한다.
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2032, 8, 4);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	// 장 마감(15:30) 뒤 조회 — postSellFlow.status가 READY다. 이 값이 게이트의 절반이다.
	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	private static final String NARRATIVE_1 = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
	private static final String NARRATIVE_2 = "장 마감 뒤 종가는 69,200원이었습니다.";

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@Autowired
	private TradeFeedbackRepository tradeFeedbackRepository;

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

	private User owner;
	private Account account;
	private Instrument stock;
	private Holding holding;
	private StockReplaySession tradeSession;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository
			.saveAndFlush(User.create("post-sell-peer-gate@finplay.com", "hash", "peergate212", VIEW_AT));
		account = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, VIEW_AT));
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST212G", "테스트종목212G", BigDecimal.valueOf(100), 10_000L, true, VIEW_AT));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		tradeSession = saveSession(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE);

		saveCandle(BUY_TIME, "69500");
		saveCandle(LocalTime.of(11, 5), "70800");
		saveCandle(SELL_TIME, "68500");
		saveCandle(LocalTime.of(15, 5), "69500");
		saveCandle(LAST_CANDLE_TIME, "69200");

		sellTrade = saveSellTrade(tradeSession);
		allocate(sellTrade, saveLot(tradeSession, BUY_TIME));
	}

	// --- 게이트가 닫힌 경우: 카드는 있지만 확정 집계 행이 없다 (NOT_YET) ---

	@Test
	@DisplayName("보유 구간에 카드가 있어도 확정 집계 행이 없으면 peerComparison=NOT_YET이라 게이트가 닫힌 채고 재생성이 일어나지 않는다")
	void keepsTheGateClosedWhenTheCardExistsButNoConfirmedStatRowYet() {
		saveCard(LocalTime.of(9, 45), LocalTime.of(9, 50));
		fakeNarrativeGenerator.enqueue(NARRATIVE_1).enqueue(NARRATIVE_2);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);

		PostSellFeedbackResponse second = getPostSellFeedback();

		// 최초 생성 1회뿐이다 — 게이트가 닫혀 있어 재생성이 시도되지 않는다.
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> assertThat(feedback.isNarrativeFinalized()).isFalse());
	}

	// --- 게이트가 열리는 경우: 보유 구간에 카드가 0건이다 (NO_EVENT) ---

	// tasks.md·spec.md §반사실·집단 비교 계산의 핵심 — 카드가 0건이면 price_move_peer_stats 확정 집계 행이
	// 애초에 생기지 않는다. 게이트를 "확정 집계 행이 있다"로 두면 이 흔한 경우에 매도 후 흐름이 반영된 서술이
	// 영원히 재생성되지 않는다. 4번 항목은 이 상태를 NOT_YET이 아니라 NO_EVENT로 판정해 게이트를 연다 — 게이트
	// 조건 자체는 이 이슈가 고치지 않았다.
	@Test
	@DisplayName("보유 구간에 카드가 0건이면 peerComparison=NO_EVENT이고 게이트가 코드 수정 없이 열려 서술이 1회 재생성된다")
	void opensTheGateWhenNoCardExistsInTheHeldWindowBecauseNoEventIsNotNotYet() {
		// 카드를 저장하지 않는다 — 보유 구간(09:30~14:40)에 카드가 0건이다.
		fakeNarrativeGenerator.enqueue(NARRATIVE_1).enqueue(NARRATIVE_2);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(first.narrative()).isEqualTo(NARRATIVE_1);

		PostSellFeedbackResponse second = getPostSellFeedback();

		// 게이트가 열려 두 번째 조회에서 LLM을 한 번 더 불러 서술을 갈아 끼운다.
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
		assertThat(second.narrative()).isEqualTo(NARRATIVE_2);
		assertThat(second.narrative()).isNotEqualTo(first.narrative());
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> {
				assertThat(feedback.getNarrative()).isEqualTo(NARRATIVE_2);
				// 재생성이 LLM 성공으로 끝나면 narrative_finalized=TRUE로 닫힌다.
				assertThat(feedback.isNarrativeFinalized()).isTrue();
			});
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private void saveCard(LocalTime windowStart, LocalTime windowEnd) {
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			windowStart + "부터 하락했습니다.",
			NarrativeSource.LLM,
			windowEnd.plusMinutes(1),
			VIEW_AT));
	}

	private StockReplaySession saveSession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt));
	}

	private void saveCandle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", VIEW_AT));
	}

	private HoldingLot saveLot(StockReplaySession session, LocalTime executedTime) {
		LocalDateTime executedAt = LocalDateTime.of(session.getServiceDate(), executedTime);
		Trade buyTrade = saveTrade(
			session, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"), null, executedAt);
		return holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L, executedAt, VIEW_AT));
	}

	private Trade saveSellTrade(StockReplaySession session) {
		return saveTrade(
			session, OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"), -15_207L,
			LocalDateTime.of(session.getServiceDate(), SELL_TIME));
	}

	private void allocate(Trade sell, HoldingLot lot) {
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sell, lot, new BigDecimal("10"), 700_000L, 105L, VIEW_AT));
	}

	private Trade saveTrade(
		StockReplaySession session,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, side, price, quantity,
			price.multiply(quantity).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}

	@TestConfiguration
	static class GateTestConfig {

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
