// GET /api/journal/buy/{buyTradeId}·GET /api/journal/sell/{sellTradeId}(투자일기 상세, 경로 분리)를 실제 MySQL로 검증하는 통합 테스트다.
package com.finplay.api.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.journal.repository.SellTradeJournalRepository;
import com.finplay.api.journal.service.JournalService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// JournalListIntegrationTest와 같은 이유로 @Transactional 자동 롤백을 쓴다 — 이 이슈의 시나리오는 조회(GET)와
// 기존 PATCH 엔드포인트 재사용뿐이라 동시성 커밋을 실제로 관찰할 필요가 없다(JournalIntegrationTest의 수동
// jdbcTemplate 정리가 필요 없는 경우). PK 충돌 픽스처는 buy_trade_journals·sell_trade_journals의 PK가 auto_increment라
// 두 테이블의 실제 카운터 값을 예측할 수 없으므로(다른 테스트 클래스가 롤백돼도 InnoDB auto_increment는 되감기지
// 않는다), ALTER TABLE(암묵적 커밋이라 트랜잭션 롤백과 충돌)이 아니라 두 테이블의 현재 max(id)보다 큰 같은 값을
// 계산해 jdbcTemplate으로 두 테이블에 명시적 id를 지정해 삽입하는 방식으로 결정론적으로 만든다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class JournalDetailIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다 (기존 선례 그대로).
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);
	private static final Long MISSING_TRADE_ID = 999_999_999L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderService orderService;

	@Autowired
	private JournalService journalService;

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
	private BuyTradeJournalRepository buyTradeJournalRepository;

	@Autowired
	private SellTradeJournalRepository sellTradeJournalRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void buyJournalDetailReturns200WithFieldsMatchingStoredValue() throws Exception {
		User user = createUser("dt-buy-ok");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "DTBOK");
		journalService.createBuyJournal(user.getId(), buyTradeId, "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.");

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.buyTradeId").value(buyTradeId))
			.andExpect(jsonPath("$.content").value("실적 발표 전 분할 매수. 5% 빠지면 손절 계획."))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:00:00"));
	}

	@Test
	void sellJournalDetailReturns200WithFieldsMatchingStoredValue() throws Exception {
		User user = createUser("dt-sell-ok");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "DTSOK").sellTradeId();
		clock.set(BASE_NOW.plusMinutes(1));
		journalService.createSellJournal(user.getId(), sellTradeId, "목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.");

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.sellTradeId").value(sellTradeId))
			.andExpect(jsonPath("$.content").value("목표가 도달해서 전량 매도. 다음엔 분할 매도 시도."))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:01:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:01:00"));
	}

	// 이 이슈의 핵심 회귀 — buy_trade_journals.id와 sell_trade_journals.id가 같은 값을 갖도록 두 테이블 모두 현재
	// max(id)보다 큰 동일한 id로 직접 삽입한 뒤, 두 경로가 각각 자기 테이블의 회고(서로 다른 본문·수정시각)만
	// 반환하는지 확인한다. 컨트롤러·서비스가 경로별로 다른 리포지토리를 조회하는 설계(경로 분리 결정)를 고정한다.
	@Test
	void detailEndpointsReturnOwnTableRowWhenPrimaryKeysCollideAcrossTables() throws Exception {
		User user = createUser("dt-pk-collide");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "DTPKB");
		Long sellTradeId = createBuyThenSellTradePair(user, "DTPKS").sellTradeId();

		Long buyMaxId = jdbcTemplate.queryForObject("select coalesce(max(id), 0) from buy_trade_journals", Long.class);
		Long sellMaxId = jdbcTemplate
			.queryForObject("select coalesce(max(id), 0) from sell_trade_journals", Long.class);
		long collidingId = Math.max(buyMaxId == null ? 0L : buyMaxId, sellMaxId == null ? 0L : sellMaxId) + 1;

		LocalDateTime buyCreatedAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
		LocalDateTime buyUpdatedAt = LocalDateTime.of(2026, 7, 21, 9, 0, 0);
		LocalDateTime sellCreatedAt = LocalDateTime.of(2026, 7, 22, 9, 0, 0);
		LocalDateTime sellUpdatedAt = LocalDateTime.of(2026, 7, 23, 9, 0, 0);

		jdbcTemplate.update(
			"insert into buy_trade_journals (id, buy_trade_id, content, created_at, updated_at) values (?, ?, ?, ?, ?)",
			collidingId, buyTradeId, "PK 충돌 매수 회고 본문", buyCreatedAt, buyUpdatedAt);
		jdbcTemplate.update(
			"insert into sell_trade_journals (id, sell_trade_id, content, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?)",
			collidingId, sellTradeId, "PK 충돌 매도 회고 본문", sellCreatedAt, sellUpdatedAt);

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(collidingId))
			.andExpect(jsonPath("$.buyTradeId").value(buyTradeId))
			.andExpect(jsonPath("$.content").value("PK 충돌 매수 회고 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-20T09:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-21T09:00:00"));

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(collidingId))
			.andExpect(jsonPath("$.sellTradeId").value(sellTradeId))
			.andExpect(jsonPath("$.content").value("PK 충돌 매도 회고 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-22T09:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-23T09:00:00"));
	}

	@Test
	void buyJournalDetailReflectsUpdatedContentAndUpdatedAtAfterPatch() throws Exception {
		User user = createUser("dt-buy-patch");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "DTBPT");
		journalService.createBuyJournal(user.getId(), buyTradeId, "최초 작성 본문");

		clock.set(BASE_NOW.plusMinutes(5));
		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"content\":\"수정된 본문\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("수정된 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:05:00"));
	}

	@Test
	void sellJournalDetailReflectsUpdatedContentAndUpdatedAtAfterPatch() throws Exception {
		User user = createUser("dt-sell-patch");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "DTSPT").sellTradeId();
		clock.set(BASE_NOW.plusMinutes(1));
		journalService.createSellJournal(user.getId(), sellTradeId, "최초 작성 본문");

		clock.set(BASE_NOW.plusMinutes(6));
		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"content\":\"수정된 본문\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("수정된 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:01:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:06:00"));
	}

	@Test
	void getBuyJournalReturns404ForMissingTrade() throws Exception {
		User user = createUser("dt-buy-notrade");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", MISSING_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void getSellJournalReturns404ForMissingTrade() throws Exception {
		User user = createUser("dt-sell-notrade");
		createAccount(user);
		String accessToken = issueAccessToken(user);

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", MISSING_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void getBuyJournalReturns404WhenJournalNotWritten() throws Exception {
		User user = createUser("dt-buy-nojournal");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "DTBNJ");

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void getSellJournalReturns404WhenJournalNotWritten() throws Exception {
		User user = createUser("dt-sell-nojournal");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "DTSNJ").sellTradeId();

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void getBuyJournalReturns403ForOtherUsersTrade() throws Exception {
		User owner = createUser("dt-buy-owner");
		createAccount(owner);
		Long ownerBuyTradeId = createBuyTrade(owner, "DTBOW");
		journalService.createBuyJournal(owner.getId(), ownerBuyTradeId, "소유자가 작성한 회고");

		User intruder = createUser("dt-buy-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", ownerBuyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderAccessToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void getSellJournalReturns403ForOtherUsersTrade() throws Exception {
		User owner = createUser("dt-sell-owner");
		createAccount(owner);
		Long ownerSellTradeId = createBuyThenSellTradePair(owner, "DTSOW").sellTradeId();
		journalService.createSellJournal(owner.getId(), ownerSellTradeId, "소유자가 작성한 회고");

		User intruder = createUser("dt-sell-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", ownerSellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderAccessToken))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	// 매수 경로에 매도 체결 ID를 넣으면(경로·체결 구분 교차) 400이어야 한다.
	@Test
	void getBuyJournalReturns400ForSellTradeIdCrossPath() throws Exception {
		User user = createUser("dt-cross-buy");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "DTCRB").sellTradeId();

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	// 매도 경로에 매수 체결 ID를 넣으면(경로·체결 구분 교차) 400이어야 한다.
	@Test
	void getSellJournalReturns400ForBuyTradeIdCrossPath() throws Exception {
		User user = createUser("dt-cross-sell");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyThenSellTradePair(user, "DTCRS").buyTradeId();

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void getBuyJournalReturns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void getSellJournalReturns401WhenUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	// 조회(성공·404·403·400·401 전 경로)가 investor journal 테이블(행 수·updated_at)과 원장 테이블 어느 것도
	// 건드리지 않는지 확인한다(spec.md — 두 서비스 메서드 모두 @Transactional(readOnly = true)).
	@Test
	void detailRequestsDoNotMutateJournalOrLedgerTables() throws Exception {
		User user = createUser("dt-invariant");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "DTINV");
		journalService.createBuyJournal(user.getId(), buyTradeId, "불변성 확인용 매수 회고");
		Long sellTradeId = createBuyThenSellTradePair(user, "DTIN2").sellTradeId();
		clock.set(BASE_NOW.plusMinutes(1));
		journalService.createSellJournal(user.getId(), sellTradeId, "불변성 확인용 매도 회고");

		LedgerSnapshot before = captureLedger(account.getId(), buyTradeId, sellTradeId);

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andExpect(status().isOk());
		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andExpect(status().isOk());
		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", MISSING_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", buyTradeId)).andExpect(status().isUnauthorized());

		assertThat(captureLedger(account.getId(), buyTradeId, sellTradeId)).isEqualTo(before);
	}

	// 매수 파이프라인만 태워 체결 1건을 만든다(주문가는 09:59에 마감된 분봉).
	private Long createBuyTrade(User user, String instrumentPrefix) {
		clock.set(BASE_NOW);
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-" + UUID.randomUUID(), buyRequest(instrument.getId(), "10"));
		return response.tradeId();
	}

	// 매수 → 매도까지 태워 매도 체결 1건을 만들고, 교차 검증에 필요한 매수 체결 id도 함께 반환한다.
	private TradePair createBuyThenSellTradePair(User user, String instrumentPrefix) {
		clock.set(BASE_NOW);
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		OrderResponse buy = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		OrderResponse sell = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-sell-" + UUID.randomUUID(),
			sellRequest(instrument.getId(), "5"));
		return new TradePair(buy.tradeId(), sell.tradeId());
	}

	private record TradePair(Long buyTradeId, Long sellTradeId) {
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
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, BASE_NOW));
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

	// nickname 컬럼은 VARCHAR(50)이라 UUID 전체(32자)를 붙이면 시나리오명이 길 때 초과할 수 있어 8자로 줄인다
	// (JournalIntegrationTest·JournalListIntegrationTest 선례와 동일).
	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// orders·trades·holdings·holding_lots·trade_allocations(전역 행 수)·buy_trade_journals·sell_trade_journals
	// (전역 행 수 + 대상 회고의 updated_at)와 요청 계좌의 현금·실현손익을 한 번에 담는다. 조회 API는 읽기 전용이므로
	// 호출 전후 이 값들이 그대로여야 한다(spec.md §비즈니스 규칙).
	private LedgerSnapshot captureLedger(Long accountId, Long buyTradeId, Long sellTradeId) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		LocalDateTime buyUpdatedAt = buyTradeJournalRepository.findByBuyTradeId(buyTradeId)
			.map(j -> j.getUpdatedAt())
			.orElse(null);
		LocalDateTime sellUpdatedAt = sellTradeJournalRepository.findBySellTradeId(sellTradeId)
			.map(j -> j.getUpdatedAt())
			.orElse(null);
		return new LedgerSnapshot(
			orderRepository.count(),
			tradeRepository.count(),
			holdingRepository.count(),
			holdingLotRepository.count(),
			tradeAllocationRepository.count(),
			buyTradeJournalRepository.count(),
			sellTradeJournalRepository.count(),
			buyUpdatedAt,
			sellUpdatedAt,
			account.getCashBalance(),
			account.getRealizedPnl());
	}

	private record LedgerSnapshot(
		long orders,
		long trades,
		long holdings,
		long holdingLots,
		long tradeAllocations,
		long buyJournals,
		long sellJournals,
		LocalDateTime buyJournalUpdatedAt,
		LocalDateTime sellJournalUpdatedAt,
		long cashBalance,
		long realizedPnl) {
	}

}
