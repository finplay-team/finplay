// 실제 인증 필터·MySQL 원장으로 코인 매도 회고가 200이고 원장·주식 경로가 그대로인지 종단 검증한다.
package com.finplay.api.feedback.service;

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
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.CandleInterval;
import com.finplay.api.market.service.CryptoCandleDto;
import com.finplay.api.market.service.FakeCryptoCandleProvider;
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

// tasks-275.md 5번 항목이다 — 이슈 #275 완료 조건의 2단계 첫 항목(코인 체결이 400이 아니라 200)과 공통 3건
// (주식 경로 불변·원장 불변·LLM 폴백)을 한 파일에서 고정한다.
//
// **실제 LLM을 부르지 않는다**(ADR-0011·PRD C-005). FakeNarrativeGenerator를 @Primary로 얹어 실패를 시나리오로
// 고정한다. 코인 봉도 FakeCryptoCandleProvider가 준다 — 빗썸 REST를 부르지 않는다.
//
// 주식 경로 회귀는 이 파일이 아니라 **기존 PostSellFeedback* 통합 테스트를 수정 없이 실행해** 확인한다.
// 여기에 주식 케이스를 복제하면 그 파일들과 같은 것을 두 곳에서 단정하게 된다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PostSellFeedbackIntegrationTest 선례).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, CryptoPostSellFeedbackE2eIntegrationTest.CryptoE2eTestConfig.class})
class CryptoPostSellFeedbackE2eIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String PATH = "/api/ai/post-sell/{tradeId}";

	// 코인 전용 심볼 — V7 시드·다른 테스트와 UNIQUE(symbol)로 부딪히지 않게 이 파일 전용 이름을 쓴다.
	private static final String SYMBOL = "E2E275";

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDateTime BUY_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(12, 0));

	// 보유 구간 안의 코인 변동 카드. reveal_time이 NULL이라 노출 게이트가 없다(§C-9).
	private static final LocalDateTime CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(10, 30));

	// §C-5 게이트가 열린 뒤 조회 — 매도 다음 날 09:00이다.
	static final LocalDateTime VIEW_AT = LocalDateTime.of(SELL_DATE.plusDays(1), LocalTime.of(9, 0));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal BUY_PRICE = new BigDecimal("70000");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	// 매수원가 700,000 + 매수수수료 105 = 700,105. 매도 685,000 − 코인 수수료 342 → 실현손익 −15,447.
	private static final long ALLOCATED_COST = 700_000L;
	private static final long ALLOCATED_BUY_FEE = 105L;
	private static final long SELL_FEE = 342L;
	private static final long REALIZED_PNL = -15_447L;

	// 조회 전후로 행이 변하면 안 되는 원장 테이블 (PeerStatsBatchServiceIntegrationTest와 같은 목록이다)
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 조회가 읽기만 해야 하는 테이블
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	// 같은 feedback 도메인이지만 이 조회의 산출물이 아닌 테이블 — 쓰기는 trade_feedbacks 하나뿐이다(FEED-007).
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
		// 매도일 일봉 — 게이트가 열린 뒤 종가·반사실이 실제로 채워지게 한다.
		cryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_DAY, List.of(candle(
			SELL_DATE.atStartOfDay(), "69200")));
		// 보유 구간의 1분봉 — 극값이 실제 값으로 채워지게 한다(보유 180분이라 200봉 상한 안이다).
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

	// --- 2단계 완료 조건 1·2 — 400이 아니라 200이고 원장 수치가 주식과 같은 계산이다 ---

	@Test
	@DisplayName("코인 매도 체결 조회가 400이 아니라 200이고 원장 수치가 채워진다")
	void returnsOkWithLedgerNumbersForACryptoSellTrade() throws Exception {
		fakeNarrativeGenerator.enqueueFailure();

		mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeId").value(sellTrade.getId()))
			.andExpect(jsonPath("$.symbol").value(SYMBOL))
			// 배분 가중평균 매수단가·매도가·수량·수수료·실현손익 — 주식과 같은 계산이다.
			.andExpect(jsonPath("$.buyPrice").value(70000.00000000))
			.andExpect(jsonPath("$.sellPrice").value(68500))
			.andExpect(jsonPath("$.quantity").value(10))
			.andExpect(jsonPath("$.fee").value(SELL_FEE))
			.andExpect(jsonPath("$.realizedPnl").value(REALIZED_PNL))
			// returnRate = −15,447 ÷ (700,000 + 105) = −0.0221 (scale 4 HALF_UP).
			.andExpect(jsonPath("$.returnRate").value(-0.0221))
			// 보유기간 = 09:00 → 12:00.
			.andExpect(jsonPath("$.holdingMinutes").value(180))
			// 코인은 재생일이 없어 시간축이 언제나 연속이다(§FEED-012 결정 0).
			.andExpect(jsonPath("$.sameSessionCompleted").value(true))
			// 체결 시각 그대로다 — 원본 거래일로 갈아 끼우는 변환이 없다.
			.andExpect(jsonPath("$.buyAt").value("2026-08-05T09:00:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-08-05T12:00:00"))
			// 보유 180분이라 1분봉 정밀도다(§FEED-012 결정 4).
			.andExpect(jsonPath("$.holdHighBasis").value("MINUTE"))
			.andExpect(jsonPath("$.holdHighPrice").value(70800));
	}

	// --- 2단계 완료 조건 3 — 보유 구간 코인 카드가 노출 게이트 없이 들어온다 ---

	// 코인 카드는 reveal_time이 NULL이다. 주식이 쓰는 노출 게이트를 코인에도 적용하면 NULL이 조건에서 탈락해
	// priceMoves가 항상 빈 배열이 되는데, 예외도 로그도 남지 않는다.
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
			// minutesAfterBuy = 09:00 → 10:30, minutesBeforeSell = 10:30 → 12:00.
			.andExpect(jsonPath("$.priceMoves[0].minutesAfterBuy").value(90))
			.andExpect(jsonPath("$.priceMoves[0].minutesBeforeSell").value(90))
			// 근거 기사는 세 조회 경로가 공유하는 로더가 채운다 — 코인 경로에서 그 값을 단언하는 유일한 자리다.
			.andExpect(jsonPath("$.priceMoves[0].sources.length()").value(1))
			.andExpect(jsonPath("$.priceMoves[0].sources[0].title").value("대형 거래소 상장 소식"));
	}

	// --- 공통 조건 — LLM 폴백 (ADR-0011) ---

	@Test
	@DisplayName("코인 체결도 LLM 실패 시 템플릿 문장으로 대체되고 narrativeStatus는 READY·source는 TEMPLATE이다")
	void fallsBackToTheTemplateSentenceForCryptoTrades() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(
			owner.getId(), sellTrade.getId());

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		// 상수 READY만 보면 공허하다 — 실제로 문장이 채워졌는지가 이 보장의 내용이다.
		assertThat(response.narrative()).isNotBlank();
	}

	// --- 공통 조건 — 원장 불변 ---

	// 행 수만 보면 값이 바뀐 UPDATE(계좌 잔액·lot 잔여수량)를 놓치므로 값 비교를 더한다
	// (ai/agent-mistakes.md 2026-08-04 "원장 불변 행 수 스냅샷" 행).
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

		// 실제로 쓰기가 일어났는데도(서술 1행) 나머지가 그대로여야 의미가 있다.
		assertThat(response.narrative()).isNotBlank();
		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		assertThat(tradeRow()).isEqualTo(tradeRowBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	// --- 픽스처 ---

	private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
	}

	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new CryptoCandleDto(
			sourceTime, price, price.add(new BigDecimal("500")), price.subtract(new BigDecimal("500")),
			price, new BigDecimal("1.5"));
	}

	// 근거 기사를 함께 심는다 — priceMoves[].sources는 세 조회 경로가 공유하는 PriceMoveSourceLoader가
	// 채우는 값인데(PR #281), 기사를 심지 않으면 로더가 통째로 비어도 이 테스트가 통과한다.
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
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, BUY_AT));
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

	// 코인 체결이라 재생세션이 null이다 — Trade.of가 코인에 세션을 주면 거부한다.
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

	/** 원장에서 값이 바뀔 수 있는 자리 — 계좌 잔액과 lot 잔여수량이다. 행 수 비교로는 UPDATE가 잡히지 않는다. */
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

		// §C-5 게이트가 열린 뒤 조회 — 매도 후 흐름·반사실이 READY인 상태를 본다.
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		// 실제 OpenAI를 부르지 않는다 (ADR-0011·PRD C-005).
		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
