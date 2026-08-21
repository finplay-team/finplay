// 고정 Clock + 대역 생성기 + 실 MySQL로 서술 재생성의 게이트 통과 1회·누적 상한·DB 전이 값을 검증한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
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

// 이슈 #208 5번 항목의 완료 조건 셋이 목표다 — 게이트 통과 후 1회, 누적 상한 초과 없음, 카드 0건인 매도도
// 재생성이 일어남.
//
// **peerComparison.status는 이슈 #212 4번 항목의 실제 판정 경로(PostSellFeedbackReader.buildPeerComparison)로
// 재현한다.** 카드가 0건이면 NO_EVENT, 카드는 있지만 price_move_peer_stats 확정 집계 행이 없으면 NOT_YET,
// holderCount < 5인 행을 직접 저장하면 INSUFFICIENT_SAMPLE이다 — 배치(3번 항목) 전체를 다시 돌릴 필요는 없다.
// 이전에는 PostSellFeedbackReader를 @MockitoBean으로 대체해 이 값을 대역 처리했지만, 그 이유(게이트가
// peerComparison.status == NOT_YET일 때만 닫히는데 4번 항목 전에는 그 값이 상수 NOT_YET이라 구조적으로 열리지
// 않음)가 4번 항목으로 해소돼 실제 판정 경로로 전환했다.
//
// **DB 값을 확인하는 것이 이 파일의 핵심이다.** writer의 두 전이 메서드가 행을 자기 트랜잭션에서 다시 읽는데,
// 이것을 "호출부가 넘긴 detached 엔티티에 전이 메서드 호출"로 바꾸면 예외도 로그도 없이 아무 일도 일어나지
// 않는다 — 메모리 객체만 보는 단정은 그때도 초록이다.
//
// 실제 LLM을 부르지 않는다 (ADR-0011·PRD C-005).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	PostSellFeedbackRegenerationIntegrationTest.RegenerationTestConfig.class})
class PostSellFeedbackRegenerationIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	// 서비스 날짜는 stock_replay_sessions의 UNIQUE(service_date)에 걸린다. 공유 Testcontainer에 트랜잭션 없이
	// 커밋하는 테스트(CandleQueryServiceIntegrationTest가 2026-08-04·08-05를 커밋한다)와 같은 날짜를 쓰면
	// 단독 실행은 통과하고 `./gradlew build` 전체에서만 Duplicate entry로 깨진다 — 그래서 이 파일 전용
	// 연도(2031)를 쓴다. 원본 거래일은 UNIQUE 대상이 아니라 그대로 둔다.
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);
	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	// 후검증 5줄을 통과하는 관찰형 문장 두 개 — 최초 생성과 재생성을 구분한다.
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
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208R", "테스트종목208R", BigDecimal.valueOf(100), 10_000L, true,
				VIEW_AT));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		tradeSession = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));

		// 매도 후 흐름의 closePrice(69,200원 · 15:27)를 실제로 재현한다 — 재생성 프롬프트 단정이 이 값을 그대로
		// 문자열로 확인한다.
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

	// --- 게이트 통과 후 1회 (완료 조건 8번) ---

	// 카드 0건(NO_EVENT) 픽스처가 이 항목의 핵심이다 — 게이트를 "확정 집계 행 존재"로 판정한 구현은 이 경우에
	// 영원히 재생성하지 않고, 카드 0건은 운영에서 가장 흔한 경우다.
	@Test
	@DisplayName("카드 0건(NO_EVENT)이어도 게이트 통과 후 첫 조회에서 재생성되고 두 번째 조회에서는 재생성되지 않는다")
	void regeneratesOnceAfterTheGateOpensEvenWithoutAnyCard() {
		// 카드를 저장하지 않는다 — 보유 구간(09:30~14:40)에 카드가 0건이라 peerComparison=NO_EVENT다.
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		// 1회차 — 최초 생성. 게이트는 열려 있지만 기존 행이 없으므로 생성 경로다.
		PostSellFeedbackResponse created = getPostSellFeedback();
		assertThat(created.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(created.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(feedbackRow()).containsEntry("narrative_finalized", false);

		// 2회차 — 게이트가 열려 있고 확정 전이므로 재생성한다.
		PostSellFeedbackResponse regenerated = getPostSellFeedback();

		assertThat(regenerated.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(regenerated.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(regenerated.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		// DB 값으로 전이를 확인한다 — detached 엔티티에 전이 메서드를 부른 구현은 여기서만 빨개진다.
		assertThat(feedbackRow())
			.containsEntry("narrative", REGENERATED_NARRATIVE)
			.containsEntry("narrative_finalized", true)
			.containsEntry("regeneration_attempts", 1);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);

		// 3회차 — 확정됐으므로 재생성하지 않는다.
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

	// 게이트가 열린 뒤의 재생성 프롬프트에는 매도 후 흐름 줄이 붙는다 — 매핑을 따로 만들면 그 줄이 빠진
	// 프롬프트로 재생성해 게이트가 무의미해진다.
	@Test
	@DisplayName("재생성 프롬프트에 매도 후 흐름 줄이 실제로 들어간다")
	void putsThePostSellFlowLineIntoTheRegenerationPrompt() {
		// 카드를 저장하지 않는다 — peerComparison=NO_EVENT로도 게이트가 열린다는 것이 이 항목의 요점이다.
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		getPostSellFeedback();
		getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.userPrompts()).hasSize(2);
		assertThat(fakeNarrativeGenerator.userPrompts().get(1))
			.contains("매도 후 흐름")
			.contains("69,200원");
	}

	// --- 누적 상한 (완료 조건 9번) ---

	// 상한 + 1회를 재현한다 — 상한 이하만 재현하면 날짜 리셋 버그를 잡지 못한다. 실패는 템플릿 폴백이며
	// 대역 생성기에 응답을 넣지 않으면 그 경로가 된다.
	@Test
	@DisplayName("재생성 실패가 누적 상한을 넘지 않고 기존 서술과 narrative_finalized=false가 유지된다")
	void neverExceedsTheCumulativeRetryLimit() {
		int limit = feedbackLlmProperties.maxNarrativeRetry();
		// 최초 생성 1회만 성공시키고 이후 재생성은 전부 실패(템플릿 폴백)로 만든다.
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE);

		getPostSellFeedback();
		assertThat(feedbackRow()).containsEntry("regeneration_attempts", 0);

		// 상한 횟수만큼 실패한다 — 매번 호출이 일어나고 횟수가 오른다.
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

		// 상한 + 1회차 — 여기서 LLM을 부르면 실패하는 체결 하나가 조회마다 호출을 내는데 응답은 정상 200이라
		// 아무 신호도 남지 않는다.
		int callsAtLimit = fakeNarrativeGenerator.callCount();
		PostSellFeedbackResponse afterLimit = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(callsAtLimit);
		assertThat(afterLimit.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(afterLimit.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(feedbackRow())
			.containsEntry("regeneration_attempts", limit)
			.containsEntry("narrative_finalized", false);
	}

	// --- 게이트가 닫힌 상태 ---

	@Test
	@DisplayName("집단 비교가 NOT_YET이면 재생성하지 않고 최초 서술을 그대로 재사용한다")
	void neverRegeneratesWhilePeerComparisonIsNotYet() {
		// 카드는 있지만 price_move_peer_stats 확정 집계 행을 저장하지 않는다 — 배치가 아직 안 돈 상태다.
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

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	/** {@code flush()}가 전제다 — 보류된 UPDATE는 raw JDBC에 보이지 않아 전이가 없는 구현도 초록이 된다. */
	private Map<String, Object> feedbackRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap(
			"SELECT narrative, narrative_source, narrative_finalized, regeneration_attempts, generated_at "
				+ "FROM trade_feedbacks WHERE trade_id = ?",
			sellTrade.getId());
	}

	/** 보유 구간(09:30~14:40) 안의 변동 카드 1건 — {@code peerComparison}의 기준 카드가 된다. */
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

	/** {@code (card, TRADE_SERVICE_DATE)} 축의 확정 집계 행 — 배치(3번 항목) 전체를 다시 돌리지 않고 직접 저장한다. */
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

		// 실제 OpenAI를 부르지 않는다 (ADR-0011·PRD C-005). 응답을 넣지 않은 회차는 실패로 수렴한다.
		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
