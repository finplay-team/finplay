package com.finplay.api.domain.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.journal.repository.BuyTradeJournalRepository;
import com.finplay.api.domain.journal.repository.SellTradeJournalRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class JournalIntegrationTest {

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

	private final List<Long> createdInstrumentIds = new ArrayList<>();
	private boolean createdReplaySessionByThisTest = false;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		if (stockReplaySessionRepository.findByServiceDate(TRADING_DATE).isEmpty()) {
			stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW));
			createdReplaySessionByThisTest = true;
		}
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("delete from buy_trade_journals");
		jdbcTemplate.update("delete from sell_trade_journals");

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

	@Test
	void updateSellJournalReturns200AndUpdatesContentAndUpdatedAtWithoutTouchingLedger() throws Exception {
		User user = createUser("ujour-success");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "UJSUC").sellTradeId();

		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "최초 작성 본문"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:01:00"));

		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchSellJournal(sellTradeId, accessToken, "수정된 본문"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.sellTradeId").value(sellTradeId))
			.andExpect(jsonPath("$.content").value("수정된 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:01:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:02:00"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(1L);
		assertThat(sellJournalContentFor(sellTradeId)).isEqualTo("수정된 본문");
		LocalDateTime[] timestamps = sellJournalTimestampsFor(sellTradeId);
		assertThat(timestamps[1]).isAfter(timestamps[0]);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void consecutiveUpdatesToSellJournalBothReturn200AndOnlyLastContentRemains() throws Exception {
		User user = createUser("ujour-consec");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "UJCON").sellTradeId();

		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "최초 작성 본문"))
			.andExpect(status().isCreated());

		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchSellJournal(sellTradeId, accessToken, "첫 번째 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("첫 번째 수정"));

		clock.set(BASE_NOW.plusMinutes(3));

		mockMvc.perform(patchSellJournal(sellTradeId, accessToken, "두 번째 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("두 번째 수정"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:03:00"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(1L);
		assertThat(sellJournalContentFor(sellTradeId)).isEqualTo("두 번째 수정");
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateMissingSellTradeReturns404AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("ujour-missing");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		LedgerSnapshot before = captureLedger(account.getId());
		long journalCountBefore = totalSellJournalCount();

		mockMvc.perform(patchSellJournal(MISSING_TRADE_ID, accessToken, "없는 체결에 대한 수정 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(totalSellJournalCount()).isEqualTo(journalCountBefore);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateUnwrittenSellJournalReturns404AndDoesNotCreateRowUpsert() throws Exception {
		User user = createUser("ujour-unwritten");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "UJUNW").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patchSellJournal(sellTradeId, accessToken, "아직 작성 안 된 회고에 대한 수정 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(sellJournalCountFor(sellTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateOtherUsersSellJournalReturns403AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User owner = createUser("ujour-owner");
		Account ownerAccount = createAccount(owner);
		String ownerAccessToken = issueAccessToken(owner);
		Long ownerSellTradeId = createBuyThenSellTradePair(owner, "UJOWN").sellTradeId();

		mockMvc.perform(postSellJournal(ownerSellTradeId, ownerAccessToken, "소유자가 작성한 회고"))
			.andExpect(status().isCreated());

		User intruder = createUser("ujour-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		LedgerSnapshot before = captureLedger(ownerAccount.getId());

		mockMvc.perform(patchSellJournal(ownerSellTradeId, intruderAccessToken, "타인 회고에 대한 수정 시도"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(sellJournalContentFor(ownerSellTradeId)).isEqualTo("소유자가 작성한 회고");
		assertThat(captureLedger(ownerAccount.getId())).isEqualTo(before);
	}

	@Test
	void updateBuyTradeReturns400ForSellJournalAndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("ujour-buy");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyThenSellTradePair(user, "UJBUY").buyTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patchSellJournal(buyTradeId, accessToken, "매수 체결에 대한 수정 시도"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(sellJournalCountFor(buyTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateBlankContentReturns400ForSellJournalAndLeavesContentAndLedgerUnchanged() throws Exception {
		User user = createUser("ujour-blank");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "UJBLK").sellTradeId();

		mockMvc.perform(postSellJournal(sellTradeId, accessToken, "원본 본문"))
			.andExpect(status().isCreated());

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody("   ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(sellJournalContentFor(sellTradeId)).isEqualTo("원본 본문");
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateBuyJournalReturns200AndUpdatesContentAndUpdatedAtWithoutTouchingLedger() throws Exception {
		User user = createUser("ubjour-success");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "UBJSC");

		mockMvc.perform(postJournal(buyTradeId, accessToken, "최초 작성 본문"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00"));

		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "수정된 본문"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").isNumber())
			.andExpect(jsonPath("$.buyTradeId").value(buyTradeId))
			.andExpect(jsonPath("$.content").value("수정된 본문"))
			.andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:02:00"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(journalContentFor(buyTradeId)).isEqualTo("수정된 본문");
		LocalDateTime[] timestamps = journalTimestampsFor(buyTradeId);
		assertThat(timestamps[1]).isAfter(timestamps[0]);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void consecutiveUpdatesToBuyJournalBothReturn200AndOnlyLastContentRemains() throws Exception {
		User user = createUser("ubjour-consec");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "UBJCN");

		mockMvc.perform(postJournal(buyTradeId, accessToken, "최초 작성 본문"))
			.andExpect(status().isCreated());

		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "첫 번째 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("첫 번째 수정"));

		clock.set(BASE_NOW.plusMinutes(3));

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "두 번째 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("두 번째 수정"))
			.andExpect(jsonPath("$.updatedAt").value("2026-07-29T10:03:00"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(journalContentFor(buyTradeId)).isEqualTo("두 번째 수정");
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateMissingBuyTradeReturns404AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("ubjour-missing");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);

		LedgerSnapshot before = captureLedger(account.getId());
		long journalCountBefore = totalJournalCount();

		mockMvc.perform(patchJournal(MISSING_TRADE_ID, accessToken, "없는 체결에 대한 수정 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(totalJournalCount()).isEqualTo(journalCountBefore);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateUnwrittenBuyJournalReturns404AndDoesNotCreateRowUpsert() throws Exception {
		User user = createUser("ubjour-unwritten");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "UBJUW");

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "아직 작성 안 된 회고에 대한 수정 시도"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateOtherUsersBuyJournalReturns403AndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User owner = createUser("ubjour-owner");
		Account ownerAccount = createAccount(owner);
		String ownerAccessToken = issueAccessToken(owner);
		Long ownerBuyTradeId = createBuyTrade(owner, "UBJOW");

		mockMvc.perform(postJournal(ownerBuyTradeId, ownerAccessToken, "소유자가 작성한 회고"))
			.andExpect(status().isCreated());

		User intruder = createUser("ubjour-intruder");
		createAccount(intruder);
		String intruderAccessToken = issueAccessToken(intruder);

		LedgerSnapshot before = captureLedger(ownerAccount.getId());

		mockMvc.perform(patchJournal(ownerBuyTradeId, intruderAccessToken, "타인 회고에 대한 수정 시도"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(journalContentFor(ownerBuyTradeId)).isEqualTo("소유자가 작성한 회고");
		assertThat(captureLedger(ownerAccount.getId())).isEqualTo(before);
	}

	@Test
	void updateSellTradeReturns400ForBuyJournalAndLeavesLedgerAndJournalTableUnchanged() throws Exception {
		User user = createUser("ubjour-sell");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long sellTradeId = createBuyThenSellTradePair(user, "UBJSL").sellTradeId();

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patchJournal(sellTradeId, accessToken, "매도 체결에 대한 매수 회고 수정 시도"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(journalCountFor(sellTradeId)).isEqualTo(0L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateBlankContentReturns400ForBuyJournalAndLeavesContentAndLedgerUnchanged() throws Exception {
		User user = createUser("ubjour-blank");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyTrade(user, "UBJBK");

		mockMvc.perform(postJournal(buyTradeId, accessToken, "원본 본문"))
			.andExpect(status().isCreated());

		LedgerSnapshot before = captureLedger(account.getId());

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody("   ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(journalContentFor(buyTradeId)).isEqualTo("원본 본문");
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateBuyJournalSucceedsWhenLotHasPartialSellAllocationRegressionNoLock() throws Exception {
		User user = createUser("ubjour-partial");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyThenSellTradePair(user, "UBJPT").buyTradeId();

		mockMvc.perform(postJournal(buyTradeId, accessToken, "부분 매도 전 매수 회고"))
			.andExpect(status().isCreated());

		assertThat(allocationCountForBuyTrade(buyTradeId)).isEqualTo(1L);
		BigDecimal remainingBefore = remainingQuantityForBuyTrade(buyTradeId);
		BigDecimal allocatedBefore = totalAllocatedQuantityForBuyTrade(buyTradeId);
		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "부분 매도된 lot에 대한 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("부분 매도된 lot에 대한 수정"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(journalContentFor(buyTradeId)).isEqualTo("부분 매도된 lot에 대한 수정");
		assertThat(remainingQuantityForBuyTrade(buyTradeId)).isEqualByComparingTo(remainingBefore);
		assertThat(totalAllocatedQuantityForBuyTrade(buyTradeId)).isEqualByComparingTo(allocatedBefore);
		assertThat(allocationCountForBuyTrade(buyTradeId)).isEqualTo(1L);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

	@Test
	void updateBuyJournalSucceedsWhenLotIsFullySoldRegressionNoLock() throws Exception {
		User user = createUser("ubjour-fullsold");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Long buyTradeId = createBuyThenFullySellTradePair(user, "UBJFS").buyTradeId();

		mockMvc.perform(postJournal(buyTradeId, accessToken, "전량 매도 전 매수 회고"))
			.andExpect(status().isCreated());

		assertThat(remainingQuantityForBuyTrade(buyTradeId)).isEqualByComparingTo(BigDecimal.ZERO);
		BigDecimal allocatedBefore = totalAllocatedQuantityForBuyTrade(buyTradeId);
		LedgerSnapshot before = captureLedger(account.getId());
		clock.set(BASE_NOW.plusMinutes(2));

		mockMvc.perform(patchJournal(buyTradeId, accessToken, "전량 매도된 lot에 대한 수정"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("전량 매도된 lot에 대한 수정"));

		assertThat(journalCountFor(buyTradeId)).isEqualTo(1L);
		assertThat(journalContentFor(buyTradeId)).isEqualTo("전량 매도된 lot에 대한 수정");
		assertThat(remainingQuantityForBuyTrade(buyTradeId)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(totalAllocatedQuantityForBuyTrade(buyTradeId)).isEqualByComparingTo(allocatedBefore);
		assertThat(captureLedger(account.getId())).isEqualTo(before);
	}

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

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchSellJournal(
		Long sellTradeId, String accessToken, String content) {
		return patch("/api/trades/{sellTradeId}/sell-journal", sellTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody(content));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchJournal(
		Long buyTradeId, String accessToken, String content) {
		return patch("/api/trades/{buyTradeId}/journal", buyTradeId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(journalBody(content));
	}

	private String journalBody(String content) {
		return "{\"content\":\"" + content + "\"}";
	}

	private Long createBuyTrade(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-" + UUID.randomUUID(), buyRequest(instrument.getId(), "10"));
		return response.tradeId();
	}

	private Long createBuyThenSellTrade(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		OrderResponse sell = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-sell-" + UUID.randomUUID(),
			sellRequest(instrument.getId(), "5"));
		return sell.tradeId();
	}

	private TradePair createBuyThenSellTradePair(User user, String instrumentPrefix) {
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

	private TradePair createBuyThenFullySellTradePair(User user, String instrumentPrefix) {
		Instrument instrument = createStockInstrument(instrumentPrefix);
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		OrderResponse buy = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-buy-" + UUID.randomUUID(),
			buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		OrderResponse sell = orderService.createOrder(
			user.getId(), "idem-" + instrumentPrefix + "-sell-" + UUID.randomUUID(),
			sellRequest(instrument.getId(), "10"));
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
			Account.create(user, Market.STOCK, BASE_NOW));
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

	private long journalCountFor(Long buyTradeId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from buy_trade_journals where buy_trade_id = ?", Long.class, buyTradeId);
		return count == null ? 0L : count;
	}

	private long totalJournalCount() {
		Long count = jdbcTemplate.queryForObject("select count(*) from buy_trade_journals", Long.class);
		return count == null ? 0L : count;
	}

	private String journalContentFor(Long buyTradeId) {
		return jdbcTemplate.queryForObject(
			"select content from buy_trade_journals where buy_trade_id = ?", String.class, buyTradeId);
	}

	private LocalDateTime[] journalTimestampsFor(Long buyTradeId) {
		return jdbcTemplate.queryForObject(
			"select created_at, updated_at from buy_trade_journals where buy_trade_id = ?",
			(rs, rowNum) -> new LocalDateTime[] {
				rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)},
			buyTradeId);
	}

	private BigDecimal remainingQuantityForBuyTrade(Long buyTradeId) {
		return jdbcTemplate.queryForObject(
			"select remaining_quantity from holding_lots where buy_trade_id = ?", BigDecimal.class, buyTradeId);
	}

	private BigDecimal totalAllocatedQuantityForBuyTrade(Long buyTradeId) {
		BigDecimal sum = jdbcTemplate.queryForObject(
			"select coalesce(sum(ta.allocated_quantity), 0) from trade_allocations ta "
				+ "join holding_lots hl on ta.holding_lot_id = hl.id where hl.buy_trade_id = ?",
			BigDecimal.class, buyTradeId);
		return sum == null ? BigDecimal.ZERO : sum;
	}

	private long allocationCountForBuyTrade(Long buyTradeId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from trade_allocations ta join holding_lots hl on ta.holding_lot_id = hl.id "
				+ "where hl.buy_trade_id = ?",
			Long.class, buyTradeId);
		return count == null ? 0L : count;
	}

	private long sellJournalCountFor(Long sellTradeId) {
		Long count = jdbcTemplate.queryForObject(
			"select count(*) from sell_trade_journals where sell_trade_id = ?", Long.class, sellTradeId);
		return count == null ? 0L : count;
	}

	private long totalSellJournalCount() {
		Long count = jdbcTemplate.queryForObject("select count(*) from sell_trade_journals", Long.class);
		return count == null ? 0L : count;
	}

	private String sellJournalContentFor(Long sellTradeId) {
		return jdbcTemplate.queryForObject(
			"select content from sell_trade_journals where sell_trade_id = ?", String.class, sellTradeId);
	}

	private LocalDateTime[] sellJournalTimestampsFor(Long sellTradeId) {
		return jdbcTemplate.queryForObject(
			"select created_at, updated_at from sell_trade_journals where sell_trade_id = ?",
			(rs, rowNum) -> new LocalDateTime[] {
				rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)},
			sellTradeId);
	}

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

}
