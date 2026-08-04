// 매수 투자일기 작성 API의 순차·동시 중복 거부와 원장 불변을 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
package com.finplay.api.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.journal.repository.SellTradeJournalRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// @Transactional을 붙이지 않는다 — 동시 중복 시나리오에서 각 스레드의 커밋 결과를 실제로 관찰해야 하기 때문이다
// (EmailChangeConcurrencyIntegrationTest와 동일한 이유). 픽스처(체결)는 mock이 아니라 실제 매수·매도 파이프라인
// (OrderService.createOrder)으로 만든다 — TradeIntegrationTest·OrderBuyIntegrationTest 선례를 따른다.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, JournalIntegrationTest.FixedClockTestConfig.class})
class JournalIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
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
	private Clock clock;

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

	// 이 테스트 인스턴스(JUnit5 기본 PER_METHOD라 메서드마다 새 인스턴스)가 실제로 커밋한 종목 id와
	// stock_replay_sessions 신규 생성 여부를 추적한다 — @AfterEach에서 이 테스트가 만든 것만 지우기 위함이다.
	// @Transactional로 통째 롤백하지 못하는 이유는 동시 중복 시나리오가 여러 스레드의 실제 커밋 결과를 봐야 하기
	// 때문이다(주석 위 설명). 대신 데이터를 남기지 않도록 명시적으로 정리한다(agent-mistakes.md 2026-07-30 재발 방지).
	private final List<Long> createdInstrumentIds = new ArrayList<>();
	private boolean createdReplaySessionByThisTest = false;

	@BeforeEach
	void setUp() {
		((MutableClock)clock).set(BASE_NOW);
		if (stockReplaySessionRepository.findByServiceDate(TRADING_DATE).isEmpty()) {
			stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW));
			createdReplaySessionByThisTest = true;
		}
	}

	@AfterEach
	void tearDown() {
		// 1. buy_trade_journals·sell_trade_journals — 이 통합 테스트 클래스만 실제로 커밋하는 테이블이라 통째로 비워도 안전하다.
		jdbcTemplate.update("delete from buy_trade_journals");
		jdbcTemplate.update("delete from sell_trade_journals");

		// 2. 이 테스트가 만든 종목에 딸린 원장 행을 FK 자식→부모 순서로 지운 뒤 종목·분봉을 지운다.
		//    (trade_allocations → holding_lots → holdings → trades → orders → stock_candles → instruments)
		for (Long instrumentId : createdInstrumentIds) {
			jdbcTemplate.update(
				"delete from trade_allocations where sell_trade_id in (select id from trades where instrument_id = ?)",
				instrumentId);
			jdbcTemplate.update(
				"delete from holding_lots where holding_id in (select id from holdings where instrument_id = ?)",
				instrumentId);
			jdbcTemplate.update("delete from holdings where instrument_id = ?", instrumentId);
			jdbcTemplate.update("delete from trades where instrument_id = ?", instrumentId);
			jdbcTemplate.update("delete from orders where instrument_id = ?", instrumentId);
			jdbcTemplate.update("delete from stock_candles where instrument_id = ?", instrumentId);
			jdbcTemplate.update("delete from instruments where id = ?", instrumentId);
		}

		// 3. stock_replay_sessions — 이 테스트가 새로 만든 경우에만 지운다. 이미 있어 재사용한 세션은
		//    다른 스위트가 쓸 수 있으므로 건드리지 않는다.
		if (createdReplaySessionByThisTest) {
			jdbcTemplate.update("delete from stock_replay_sessions where service_date = ?", TRADING_DATE);
		}
	}

	@Test
	void createBuyJournalReturns201AndPersistsExactlyOneRowWithoutTouchingLedger() throws Exception {
		User user = createUser("jour-success");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "JRSUC");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody("실적 발표 전 분할 매수. 5% 빠지면 손절 계획.")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.buyTradeId").value(buyTradeId))
			.andExpect(jsonPath("$.content").value("실적 발표 전 분할 매수. 5% 빠지면 손절 계획."))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void sequentialDuplicateJournalWriteFailsOnSecondAttemptAndKeepsSingleRow() throws Exception {
		User user = createUser("jour-seq-dup");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "JRSEQ");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postJournal(buyTradeId, accessToken, "첫 작성"))
			.andExpect(status().isCreated());
		mockMvc.perform(postJournal(buyTradeId, accessToken, "두 번째 시도"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void concurrentDuplicateJournalWritesLetExactlyOneRequestSucceedAndKeepSingleRow() throws Exception {
		User user = createUser("jour-con-dup");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "JRCON");

		LedgerSnapshot before = captureLedger(account.getId());

		List<Integer> statuses = fireConcurrentJournalRequests(buyTradeId, accessToken, 2);

		// 잠금이 없으면 두 요청 모두 existsByBuyTradeId=false를 보고 둘 다 저장을 시도하는데,
		// UNIQUE(buy_trade_id) 제약이 한 건만 통과시키고 나머지는 DataIntegrityViolationException → 409로 변환된다.
		assertThat(statuses).hasSize(2).containsExactlyInAnyOrder(201, 409);
		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void missingBuyTradeReturns404AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("jour-missing");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		LedgerSnapshot before = captureLedger(account.getId());
		long journalCountBefore = totalJournalCount();

		mockMvc.perform(postJournal(MISSING_TRADE_ID, accessToken, "없는 체결에 대한 작성 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(totalJournalCount()).isEqualTo(journalCountBefore);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void sellTradeReturns400AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("jour-sell");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTrade(user, "JRSEL");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postJournal(sellTradeId, accessToken, "매도 체결에 대한 작성 시도"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(journalCountFor(sellTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void otherUsersTradeReturns403AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User owner = createUser("jour-owner");
		Account ownerAccount = createAccount(owner);
		Long ownerTradeId = createBuyTrade(owner, "JROWN");

		User intruder = createUser("jour-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		LedgerSnapshot before = captureLedger(ownerAccount.getId());

		mockMvc.perform(postJournal(ownerTradeId, intruderAccessToken, "타인 체결에 대한 작성 시도"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(journalCountFor(ownerTradeId)).isEqualTo(0L);
		assertThat(captureLedger(ownerAccount.getId())).isEqualTo(before);
	}

	@Test
	void blankContentReturns400AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("jour-blank");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "JRBLK");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody("   ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void createSellJournalReturns201AndPersistsExactlyOneRowWithoutTouchingLedger() throws Exception {
		User user = createUser("sjour-success");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "SJSUC").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "목표가 도달해서 전량 매도. 다음엔 분할 매도 시도."))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.sellTradeId").value(sellTradeId))
			.andExpect(jsonPath("$.content").value("목표가 도달해서 전량 매도. 다음엔 분할 매도 시도."))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:01:00"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void sequentialDuplicateSellJournalWriteFailsOnSecondAttemptAndKeepsSingleRow() throws Exception {
		User user = createUser("sjour-seq-dup");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "SJSEQ").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "첫 작성"))
			.andExpect(status().isCreated());
		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "두 번째 시도"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void concurrentDuplicateSellJournalWritesLetExactlyOneRequestSucceedAndKeepSingleRow() throws Exception {
		User user = createUser("sjour-con-dup");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "SJCON").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		List<Integer> statuses = fireConcurrentSellJournalRequests(sellTradeId, accessToken, 2);

		// 잠금이 없으면 두 요청 모두 existsBySellTradeId=false를 보고 둘 다 저장을 시도하는데,
		// UNIQUE(sell_trade_id) 제약이 한 건만 통과시키고 나머지는 DataIntegrityViolationException → 409로 변환된다.
		assertThat(statuses).hasSize(2).containsExactlyInAnyOrder(201, 409);
		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void missingSellTradeReturns404AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("sjour-missing");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		LedgerSnapshot before = captureLedger(account.getId());
		long journalCountBefore = totalSellJournalCount();

		mockMvc.perform(postSellJournal(MISSING_TRADE_ID, accessToken, "없는 체결에 대한 작성 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(totalSellJournalCount()).isEqualTo(journalCountBefore);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void buyTradeReturns400ForSellJournalAndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("sjour-buy");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyThenSellTradePair(user, "SJBUY").buyTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postSellJournal(buyTradeId, accessToken, "매수 체결에 대한 매도 회고 작성 시도"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(sellJournalCountFor(buyTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void otherUsersSellTradeReturns403AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User owner = createUser("sjour-owner");
		Account ownerAccount = createAccount(owner);
		Long ownerSellTradeId = createBuyThenSellTradePair(owner, "SJOWN").sellTradeId();

		User intruder = createUser("sjour-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		LedgerSnapshot before = captureLedger(ownerAccount.getId());

		mockMvc.perform(postSellJournal(ownerSellTradeId, intruderAccessToken, "타인 체결에 대한 작성 시도"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(sellJournalCountFor(ownerSellTradeId)).isEqualTo(0L);
		assertThat(captureLedger(ownerAccount.getId())).isEqualTo(before);
	}

	@Test
	void blankContentReturns400ForSellJournalAndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("sjour-blank");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "SJBLK").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody("   ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void buyAndSellJournalsForSameInstrumentTradesAreIndependentAndBothReturn201() throws Exception {
		User user = createUser("jour-both");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		TradePair pair = createBuyThenSellTradePair(user, "JRBOTH");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(postJournal(pair.buyTradeId(), accessToken, "매수 회고"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.buyTradeId").value(pair.buyTradeId()));
		mockMvc.perform(postSellJournal(pair.sellTradeId(), accessToken, "매도 회고"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sellTradeId").value(pair.sellTradeId()));

		assertThat(journalCountFor(pair.buyTradeId())).isEqualTo(1L);
		assertThat(sellJournalCountFor(pair.sellTradeId())).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	// 같은 체결에 동시에 투자일기 작성 요청을 쏘고 각 응답 상태를 모은다 (EmailChangeConcurrencyIntegrationTest 선례).
	private List<Integer> fireConcurrentJournalRequests(Long buyTradeId, String accessToken, int count)
		throws Exception {

		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					allThreadsReady.countDown();
					startGate.await();
					return mockMvc.perform(postJournal(buyTradeId, accessToken, "동시 작성 시도 " + index))
						.andReturn()
						.getResponse()
						.getStatus();
				}));
			}
			assertThat(allThreadsReady.await(30, TimeUnit.SECONDS)).isTrue();
			startGate.countDown();

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	// 같은 체결에 동시에 매도 회고 작성 요청을 쏘고 각 응답 상태를 모은다 (fireConcurrentJournalRequests와 대칭).
	private List<Integer> fireConcurrentSellJournalRequests(Long sellTradeId, String accessToken, int count)
		throws Exception {

		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					allThreadsReady.countDown();
					startGate.await();
					return mockMvc.perform(postSellJournal(sellTradeId, accessToken, "동시 작성 시도 " + index))
						.andReturn()
						.getResponse()
						.getStatus();
				}));
			}
			assertThat(allThreadsReady.await(30, TimeUnit.SECONDS)).isTrue();
			startGate.countDown();

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJournal(
		Long buyTradeId, String accessToken, String content) {
		return post("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody(content));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postSellJournal(
		Long sellTradeId, String accessToken, String content) {
		return post("/api/trades/{sellTradeId}/sell-journal", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody(content));
	}

	private String journalBody(String content) {
		return "{\"content\":\"" + content + "\"}";
	}

	// 매수 파이프라인만 태워 체결 1건을 만든다(주문가는 09:59에 마감된 분봉).
	private Long createBuyTrade(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-" + UUID.randomUUID(), buyRequest(instrument.getId(), "10"));
		return response.tradeId();
	}

	// 매수 후 매도까지 태워 매도 체결 1건을 만든다 (TradeIntegrationTest 선례와 동일한 시각 전진 방식).
	private Long createBuyThenSellTrade(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(instrument.getId(), "10"));
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));
		OrderResponse sell = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-sell-" + UUID.randomUUID(),
			sellRequest(instrument.getId(), "5"));
		return sell.tradeId();
	}

	// createBuyThenSellTrade와 같은 매수 → 매도 파이프라인을 태우되, 교차 검증(매수 체결에 sell-journal,
	// 한 종목의 매수·매도 각각에 회고 작성)에 필요한 매수 체결 id도 함께 반환한다.
	private TradePair createBuyThenSellTradePair(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		OrderResponse buy = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(instrument.getId(), "10"));
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));
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
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
		createdInstrumentIds.add(instrument.getId());
		return instrument;
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

	// buy_trade_journals에서 특정 체결에 실제 저장된 행 수를 잰다 — 다른 테스트 메서드가 같은 테이블에 남긴
	// 행과 섞이지 않도록 buy_trade_id로 스코프를 좁힌다.
	private long journalCountFor(Long buyTradeId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from buy_trade_journals where buy_trade_id = ?", Long.class, buyTradeId);
		return count == null ? 0L : count;
	}

	private long totalJournalCount() {
		Long count = jdbcTemplate.queryForObject("select count(*) from buy_trade_journals", Long.class);
		return count == null ? 0L : count;
	}

	// sell_trade_journals에서 특정 체결에 실제 저장된 행 수를 잰다 (journalCountFor와 대칭).
	private long sellJournalCountFor(Long sellTradeId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from sell_trade_journals where sell_trade_id = ?", Long.class, sellTradeId);
		return count == null ? 0L : count;
	}

	private long totalSellJournalCount() {
		Long count = jdbcTemplate.queryForObject("select count(*) from sell_trade_journals", Long.class);
		return count == null ? 0L : count;
	}

	// orders·trades·holdings·holding_lots·trade_allocations(전역 행 수)와 요청 계좌의 현금·실현손익을 한 번에 담는다.
	// 투자일기 작성 성공·실패 어느 경로에서도 이 값들이 그대로여야 한다(spec.md 비즈니스 규칙 — 원장은 읽기 전용).
	private LedgerSnapshot captureLedger(Long accountId) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		return new LedgerSnapshot(
			orderRepository.count(),
			tradeRepository.count(),
			holdingRepository.count(),
			holdingLotRepository.count(),
			tradeAllocationRepository.count(),
			account.getCashBalance(),
			account.getRealizedPnl());
	}

	private record LedgerSnapshot(
		long orders,
		long trades,
		long holdings,
		long holdingLots,
		long tradeAllocations,
		long cashBalance,
		long realizedPnl) {
	}

	// 전역 Clock 빈(ClockConfig, Asia/Seoul 실시각)을 이 테스트 컨텍스트에서만 고정 시각으로 교체한다.
	// 주식 장중 판정·분봉 조회·투자일기 작성시각이 실제 실행 시각에 의존하지 않도록 하기 위함이다.
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new MutableClock(BASE_NOW.atZone(KST).toInstant(), KST);
		}
	}

	// 매수·매도 시나리오에서 서로 다른 분봉(시각이 다른)을 각각 체결가로 쓰기 위해 테스트 도중 시각을 전진시킬 수 있는 Clock 구현.
	private static final class MutableClock extends Clock {

		private final ZoneId zone;
		private volatile Instant instant;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(zone).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
