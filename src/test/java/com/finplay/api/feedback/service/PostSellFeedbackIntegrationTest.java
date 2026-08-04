// 실제 인증 필터·MySQL 원장으로 매도 직후 피드백 조회의 수치 요약과 404·403·400 계약을 종단 검증한다.
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
import com.finplay.api.journal.repository.SellTradeJournalRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
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

// 이슈 #208 1번 항목의 완료 조건 다섯 중 "투자일기 없이 200"과 "API 계약 404·403·400"이 실제 원장 위에서만
// 확인되는 것들이다 — 배분 요약·소유권 검증·직렬화가 한 요청에 붙는 자리라 mock으로 끝내지 않는다(ADR-0003).
//
// 픽스처 수치는 docs/api-contracts.md의 예시 그대로다: 매수 700,000 + 수수료 105, 매도 685,000 − 수수료 102,
// realizedPnl −15,207, returnRate −0.0217. 계약이 "값이 안 맞으면 예시가 아니라 구현이 틀린 것"이라 적어 뒀다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
// MockMvc 호출은 같은 스레드라 이 트랜잭션 안에서 보인다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class PostSellFeedbackIntegrationTest {

	private static final String PATH = "/api/ai/post-sell/{tradeId}";

	// 원본 거래일과 서비스 날짜를 다르게 둔다 — buyAt·sellAt이 원본 거래일 축인지가 여기서 드러난다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	// 서비스 날짜는 stock_replay_sessions의 UNIQUE(service_date)에 걸린다. 공유 Testcontainer에 트랜잭션 없이
	// 커밋하는 테스트(CandleQueryServiceIntegrationTest가 2026-08-04·08-05를 커밋한다)와 같은 날짜를 쓰면
	// 단독 실행은 통과하고 `./gradlew build` 전체에서만 Duplicate entry로 깨진다 — 그래서 이 파일 전용
	// 연도(2031)를 쓴다. 원본 거래일은 UNIQUE 대상이 아니라 그대로 둔다.
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
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208I", "테스트종목208I", BigDecimal.valueOf(100), 10_000L, true, NOW));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, NOW));
		accessToken = jwtTokenProvider.issue(owner.getId(), owner.getRole()).accessToken();
	}

	// 완료 조건 2번·12번 — 두 lot에 배분된 매도이고 투자일기가 없는 건이다.
	@Test
	@DisplayName("투자일기 없이 매수·매도한 두 lot 배분 건도 200이고 수치가 계약 예시대로 나온다")
	void returnsLedgerSummaryForTwoLotSellWithoutAnyJournal() throws Exception {
		StockReplaySession session = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		Trade sellTrade = saveSellTrade(session);
		HoldingLot earliest = saveLot(session, EARLIEST_BUY_TIME, new BigDecimal("4"));
		HoldingLot later = saveLot(session, LATER_BUY_TIME, new BigDecimal("6"));
		// 나중 lot을 먼저 배분해도 가장 이른 lot이 buyAt이 된다.
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isFalse();

		String body = mockMvc.perform(authorized(get(PATH, sellTrade.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeId").value(sellTrade.getId()))
			.andExpect(jsonPath("$.symbol").value("TEST208I"))
			// 조회한 날짜도 서비스 날짜(2026-08-05)도 아니라 원본 거래일이다.
			.andExpect(jsonPath("$.buyAt").value("2026-07-29T09:30:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-07-29T14:40:00"))
			.andExpect(jsonPath("$.fee").value(102))
			.andExpect(jsonPath("$.realizedPnl").value(-15207))
			.andExpect(jsonPath("$.holdingMinutes").value(310))
			.andExpect(jsonPath("$.sameSessionCompleted").value(true))
			.andExpect(jsonPath("$.priceMoves.length()").value(0))
			// 매도 후 흐름·반사실·집단 비교는 3번 항목이 채웠고 그 값이 장 마감 게이트에 걸린다. 이 클래스는
			// 실제 시계를 쓰므로 조회 시각에 따라 NOT_YET·READY가 갈린다 — 게이트 단정은 고정 Clock을 쓰는
			// PostSellFeedbackGateIntegrationTest가 맡고, 여기서는 필드가 존재하는지만 본다.
			.andExpect(jsonPath("$.postSellFlow.status").isNotEmpty())
			.andExpect(jsonPath("$.counterfactuals.status").isNotEmpty())
			.andExpect(jsonPath("$.peerComparison.status").value("NOT_YET"))
			// 서술은 4번 항목이 채웠다. 이 클래스에는 대역 생성기가 없어 api-key가 `not-configured`인 실제
			// 생성기가 실패를 돌려주고 §템플릿 문장으로 폴백한다 — 외부 호출 없이도 서술이 비지 않는다.
			.andExpect(jsonPath("$.narrative").isNotEmpty())
			.andExpect(jsonPath("$.narrativeSource").value("TEMPLATE"))
			.andExpect(jsonPath("$.narrativeStatus").value("READY"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		// jsonPath의 수 비교는 파서가 부동소수로 접어 scale이 보이지 않으므로 본문에서 직접 확인한다.
		// 700,000 ÷ 10 = 70,000 (scale 8), −15,207 ÷ (700,000 + 105) = −0.0217 (scale 4 HALF_UP).
		assertThat(body).contains("\"buyPrice\":70000.00000000");
		assertThat(body).contains("\"returnRate\":-0.0217");
	}

	// 완료 조건 3번 — 가장 이른 lot의 원본 거래일은 매도와 같고 나중 lot만 다르다. "가장 이른 lot만 보는 구현"은
	// 이 픽스처에서 true를 내므로 실제로 빨개진다(두 lot이 같은 거래일인 픽스처로는 두 구현이 같은 답을 낸다).
	@Test
	@DisplayName("나중 lot의 원본 거래일이 다르면 sameSessionCompleted=false다")
	void returnsSameSessionCompletedFalseWhenOnlyALaterLotIsFromAnotherOriginTradeDate() throws Exception {
		StockReplaySession sellSession = saveSession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		// 같은 원본 거래일을 두 서비스 날짜에 재생한 상황 — 수집이 하루 실패하면 실제로 이렇게 된다.
		StockReplaySession earliestLotSession = saveSession(EARLIER_SERVICE_DATE, ORIGIN_TRADE_DATE);
		StockReplaySession otherDateSession = saveSession(MIDDLE_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		Trade sellTrade = saveSellTrade(sellSession);
		HoldingLot earliest = saveLot(earliestLotSession, EARLIEST_BUY_TIME, new BigDecimal("4"));
		HoldingLot later = saveLot(otherDateSession, LATER_BUY_TIME, new BigDecimal("6"));
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);

		// 픽스처 자기검증 — 가장 이른 lot만 대조하면 true가 나오는 배치다.
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

	// 완료 조건 17번 — 실제 원장·인증 위에서 본다.
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
			Account.create(stranger, com.finplay.api.account.domain.Market.STOCK, NOW));
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

	// 완료 조건 1번 — 코인 매도는 빈 값을 채운 200이 아니라 400이다. 코인 체결에는 재생세션이 없다.
	@Test
	@DisplayName("코인 매도 체결은 400이고 빈 값 200을 돌려주지 않는다")
	void returnsBadRequestForCryptoSellTradeInsteadOfEmptyOkResponse() throws Exception {
		Account cryptoAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		Instrument coin = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, "TESTC208", "테스트코인208", BigDecimal.valueOf(1), 5_000L, true, NOW));
		Trade cryptoSell = saveTrade(
			cryptoAccount, coin, null, OrderSide.SELL, new BigDecimal("1"), new BigDecimal("100000000"),
			1_000L, LocalDateTime.of(SERVICE_DATE, SELL_TIME));

		mockMvc.perform(authorized(get(PATH, cryptoSell.getId())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.tradeId").doesNotExist());
	}

	@Test
	@DisplayName("토큰 없이 호출하면 401이다")
	void returnsUnauthorizedWithoutToken() throws Exception {
		mockMvc.perform(get(PATH, 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	// --- 픽스처 ---

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
