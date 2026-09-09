package com.finplay.api.domain.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeRunFillKindDto;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TradeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

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
	private EntityManager entityManager;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PracticePriceSessionRepository practicePriceSessionRepository;

	@Autowired
	private PracticeAttemptRepository practiceAttemptRepository;

	private User owner;
	private Account ownerAccount;
	private Instrument instrument;
	private StockReplaySession session;
	private Long practiceAttemptId;
	private int idempotencySequence = 0;

	private Order createOrder(User user, Account account, LocalDateTime requestedAt) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "cursor-idem-" + idempotencySequence,
			String.valueOf(hashChar).repeat(64), requestedAt));
	}

	private Trade createTradeWithSide(
		User user, Account account, Instrument tradedInstrument, StockReplaySession replaySession, OrderSide side) {
		idempotencySequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			user, account, tradedInstrument, side, OrderType.MARKET,
			BigDecimal.valueOf(10), "rebuild-idem-" + idempotencySequence,
			String.format("%064d", idempotencySequence), NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, tradedInstrument, replaySession, side,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L,
			side == OrderSide.SELL ? 0L : null, NOW, NOW));
	}

	private Trade createTrade(Order order, Account account, LocalDateTime executedAt) {
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L, null, executedAt, executedAt));
	}

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("trade-owner@finplay.com", "hash", "tradeowner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TRD01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(20), NOW.toLocalDate(), NOW, NOW));
		practiceAttemptId = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(owner.getId(), Market.STOCK, NOW)).getId();
	}

	@Test
	@DisplayName("주문 ID로 체결을 조회한다")
	void findsTradeByOrderIdWhenExists() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-1", "j".repeat(64), NOW));
		Trade trade = tradeRepository.saveAndFlush(Trade.of(
			order, ownerAccount, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L, null, NOW, NOW));

		var result = tradeRepository.findByOrderId(order.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(trade.getId());
	}

	@Test
	@DisplayName("체결이 없는 주문 ID는 빈 값을 반환한다")
	void findByOrderIdReturnsEmptyWhenNotFound() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-2", "k".repeat(64), NOW));

		var result = tradeRepository.findByOrderId(order.getId());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("다른 계좌의 체결은 제외하고 계좌 단위로 커서 조회한다")
	void findByAccountIdWithCursorExcludesOtherAccountTrades() {
		User other = userRepository.saveAndFlush(User.create("cursor-other@finplay.com", "hash", "cursorother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, Market.STOCK, NOW));

		Order ownerOrder = createOrder(owner, ownerAccount, NOW);
		Trade ownerTrade = createTrade(ownerOrder, ownerAccount, NOW);
		Order otherOrder = createOrder(other, otherAccount, NOW);
		createTrade(otherOrder, otherAccount, NOW);

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Trade::getId).containsExactly(ownerTrade.getId());
	}

	@Test
	@DisplayName("샌드박스 종목 체결은 제외하고 실제 종목 체결만 커서 조회한다")
	void findByAccountIdWithCursorExcludesSandboxInstrumentTrades() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		Order realOrder = createOrder(owner, ownerAccount, NOW);
		Trade realTrade = createTrade(realOrder, ownerAccount, NOW);

		idempotencySequence++;
		Order sandboxOrder = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, sandboxInstrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "cursor-sandbox-trade-idem-" + idempotencySequence,
			String.valueOf((char)('a' + idempotencySequence)).repeat(64), NOW));
		tradeRepository.saveAndFlush(Trade.of(
			sandboxOrder, ownerAccount, sandboxInstrument, session, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L, null, NOW, NOW));

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Trade::getId).containsExactly(realTrade.getId());
	}

	@Test
	@DisplayName("executedAt 내림차순, 동시각이면 id 내림차순으로 정렬해 반환한다")
	void findByAccountIdWithCursorSortedByExecutedAtThenIdDescending() {
		Order olderOrder = createOrder(owner, ownerAccount, NOW);
		Trade older = createTrade(olderOrder, ownerAccount, NOW.minusMinutes(10));
		Order sameTimeFirstOrder = createOrder(owner, ownerAccount, NOW);
		Trade sameTimeFirst = createTrade(sameTimeFirstOrder, ownerAccount, NOW);
		Order sameTimeSecondOrder = createOrder(owner, ownerAccount, NOW);
		Trade sameTimeSecond = createTrade(sameTimeSecondOrder, ownerAccount, NOW);

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Trade::getId)
			.containsExactly(sameTimeSecond.getId(), sameTimeFirst.getId(), older.getId());
	}

	@Test
	@DisplayName("커서로 연속 조회한 결과가 커서 없이 한 번에 조회한 전체 결과와 중복·누락 없이 일치한다")
	void cursorPaginationMatchesFullResultWithoutDuplicatesOrGaps() {
		List<Trade> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Order order = createOrder(owner, ownerAccount, NOW);
			created.add(createTrade(order, ownerAccount, NOW.minusMinutes(i)));
		}

		List<Trade> fullResult = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);
		assertThat(fullResult).hasSize(5);

		List<Trade> firstPage = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 3);
		Trade lastOfFirstPage = firstPage.get(firstPage.size() - 1);
		List<Trade> secondPage = tradeRepository.findByAccountIdWithCursor(
			ownerAccount.getId(), lastOfFirstPage.getExecutedAt(), lastOfFirstPage.getId(), 3);

		List<Long> pagedIds = new ArrayList<>();
		firstPage.forEach(trade -> pagedIds.add(trade.getId()));
		secondPage.forEach(trade -> pagedIds.add(trade.getId()));

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(fullResult.stream().map(Trade::getId).toList());
	}

	@Test
	@DisplayName("동일 executedAt 그룹 안에서 페이지가 나뉘어도 id 내림차순 커서로 중복·누락 없이 이어받는다")
	void cursorPaginationSplitsWithinSameExecutedAtGroupWithoutDuplicatesOrGaps() {
		Order order1 = createOrder(owner, ownerAccount, NOW);
		Trade trade1 = createTrade(order1, ownerAccount, NOW);
		Order order2 = createOrder(owner, ownerAccount, NOW);
		Trade trade2 = createTrade(order2, ownerAccount, NOW);
		Order order3 = createOrder(owner, ownerAccount, NOW);
		Trade trade3 = createTrade(order3, ownerAccount, NOW);
		Order order4 = createOrder(owner, ownerAccount, NOW);
		Trade trade4 = createTrade(order4, ownerAccount, NOW);

		List<Trade> fullResult = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);
		assertThat(fullResult).extracting(Trade::getId)
			.containsExactly(trade4.getId(), trade3.getId(), trade2.getId(), trade1.getId());

		List<Trade> firstPage = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 2);
		Trade lastOfFirstPage = firstPage.get(firstPage.size() - 1);
		List<Trade> secondPage = tradeRepository.findByAccountIdWithCursor(
			ownerAccount.getId(), lastOfFirstPage.getExecutedAt(), lastOfFirstPage.getId(), 2);

		List<Long> pagedIds = new ArrayList<>();
		firstPage.forEach(trade -> pagedIds.add(trade.getId()));
		secondPage.forEach(trade -> pagedIds.add(trade.getId()));

		assertThat(pagedIds).hasSize(4).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(fullResult.stream().map(Trade::getId).toList());
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다")
	void findByAccountIdWithCursorFetchesInstrumentWithoutLazyInitException() {
		Order order = createOrder(owner, ownerAccount, NOW);
		createTrade(order, ownerAccount, NOW);
		entityManager.clear();

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(trade -> trade.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}

	@Test
	void stockReplaySessionColumnIsNullableAndHasForeignKeyInMySql() {
		String nullable = jdbcTemplate.queryForObject("""
			SELECT IS_NULLABLE
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'trades'
			  AND COLUMN_NAME = 'stock_replay_session_id'
			""", String.class);
		Integer foreignKeyCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.KEY_COLUMN_USAGE
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'trades'
			  AND COLUMN_NAME = 'stock_replay_session_id'
			  AND REFERENCED_TABLE_NAME = 'stock_replay_sessions'
			  AND REFERENCED_COLUMN_NAME = 'id'
			""", Integer.class);

		assertThat(nullable).isEqualTo("YES");
		assertThat(foreignKeyCount).isEqualTo(1);
	}

	@Test
	void persistsStockTradeWithReplaySessionAndCryptoTradeWithoutSession() {
		StockReplaySession session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(10), NOW.toLocalDate().minusDays(1), NOW, NOW));
		Order stockOrder = createOrder(owner, ownerAccount, NOW);
		Trade stockTrade = tradeRepository.saveAndFlush(Trade.of(
			stockOrder, ownerAccount, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.ONE, 100L, 0L, null, NOW, NOW));

		Instrument crypto = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, "BTC-T", "테스트코인", new BigDecimal("0.00000001"), 5000L, true, NOW));
		Order cryptoOrder = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, crypto, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			"crypto-session-null", "z".repeat(64), NOW));
		Trade cryptoTrade = tradeRepository.saveAndFlush(Trade.of(
			cryptoOrder, ownerAccount, crypto, null, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.ONE, 100L, 0L, null, NOW, NOW));
		entityManager.clear();

		assertThat(tradeRepository.findById(stockTrade.getId()).orElseThrow().getStockReplaySession().getId())
			.isEqualTo(session.getId());
		assertThat(tradeRepository.findById(cryptoTrade.getId()).orElseThrow().getStockReplaySession()).isNull();
	}

	@Test
	@DisplayName("매도 이력 계좌 id를 중복 없이, 요청한 시장으로 한정해, 매수만 있는 계좌는 빼고 조회한다")
	void findDistinctAccountIdsBySideAndMarketDeduplicatesAndScopesByMarket() {
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		User buyer = userRepository.saveAndFlush(
			User.create("rebuild-buyer@finplay.com", "hash", "rebuildbuyer", NOW));
		Account buyOnlyAccount = accountRepository.saveAndFlush(
			Account.create(buyer, Market.STOCK, NOW));
		createTradeWithSide(buyer, buyOnlyAccount, instrument, session, OrderSide.BUY);

		Account ownerCryptoAccount = accountRepository.saveAndFlush(
			Account.create(owner, Market.CRYPTO, NOW));
		Instrument crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "RBLDC", "재구성테스트코인", new BigDecimal("0.00000001"), 5_000L, true, NOW));
		createTradeWithSide(owner, ownerCryptoAccount, crypto, null, OrderSide.SELL);

		List<Long> stockAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, Market.STOCK);
		List<Long> cryptoAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, Market.CRYPTO);

		assertThat(stockAccountIds)
			.doesNotHaveDuplicates()
			.contains(ownerAccount.getId())
			.doesNotContain(buyOnlyAccount.getId(), ownerCryptoAccount.getId());
		assertThat(cryptoAccountIds)
			.doesNotHaveDuplicates()
			.contains(ownerCryptoAccount.getId())
			.doesNotContain(ownerAccount.getId());
	}

	@Test
	@DisplayName("realized_pnl이 0이어도 매도 이력이 있으면 포함하고, realized_pnl이 0이 아니어도 매도 이력이 없으면 제외한다")
	void findDistinctAccountIdsBySideAndMarketKeysOnSellSideNotRealizedPnl() {
		Trade zeroPnlSellTrade = createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		User pnlOnlyUser = userRepository.saveAndFlush(
			User.create("rebuild-pnl-only@finplay.com", "hash", "rebuildpnlonly", NOW));
		Account pnlOnlyAccount = Account.create(pnlOnlyUser, Market.STOCK, NOW);
		pnlOnlyAccount.addRealizedPnl(123_456L);
		accountRepository.saveAndFlush(pnlOnlyAccount);
		createTradeWithSide(pnlOnlyUser, pnlOnlyAccount, instrument, session, OrderSide.BUY);

		entityManager.flush();
		entityManager.clear();
		Account reloadedSellAccount = accountRepository.findById(ownerAccount.getId()).orElseThrow();
		Account reloadedPnlOnlyAccount = accountRepository.findById(pnlOnlyAccount.getId()).orElseThrow();

		List<Long> accountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, Market.STOCK);

		assertThat(zeroPnlSellTrade.getRealizedPnl()).isZero();
		assertThat(reloadedSellAccount.getRealizedPnl()).isZero();
		assertThat(reloadedPnlOnlyAccount.getRealizedPnl()).isNotZero();
		assertThat(accountIds)
			.contains(ownerAccount.getId())
			.doesNotContain(pnlOnlyAccount.getId());
	}

	@Test
	@DisplayName("realized_pnl이 0인 계좌도 매도 이력이 있으면 true로 판정한다")
	void existsByAccountIdAndSideIsTrueForZeroRealizedPnlAccount() {
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		entityManager.flush();
		entityManager.clear();

		assertThat(accountRepository.findById(ownerAccount.getId()).orElseThrow().getRealizedPnl()).isZero();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			ownerAccount.getId(), OrderSide.SELL)).isTrue();
	}

	@Test
	@DisplayName("계좌별 매도 이력 유무를 방향(side)까지 구분해 판정한다")
	void existsByAccountIdAndSideDistinguishesSellHistoryPerAccount() {
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		User buyer = userRepository.saveAndFlush(
			User.create("rebuild-exists-buyer@finplay.com", "hash", "rebuildexists", NOW));
		Account buyOnlyAccount = accountRepository.saveAndFlush(
			Account.create(buyer, Market.STOCK, NOW));
		createTradeWithSide(buyer, buyOnlyAccount, instrument, session, OrderSide.BUY);

		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			ownerAccount.getId(), OrderSide.SELL)).isTrue();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			buyOnlyAccount.getId(), OrderSide.SELL)).isFalse();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			buyOnlyAccount.getId(), OrderSide.BUY)).isTrue();
	}

	@Test
	@DisplayName("샌드박스 종목만 매도한 계좌는 대상자 목록에서 매도 이력 없는 계좌와 동일하게 제외된다")
	void findDistinctAccountIdsBySideAndMarketExcludesSandboxOnlySellAccount() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		User sandboxOnlySeller = userRepository.saveAndFlush(
			User.create("sandbox-only-seller@finplay.com", "hash", "sandboxonly", NOW));
		Account sandboxOnlyAccount = accountRepository.saveAndFlush(
			Account.create(sandboxOnlySeller, Market.STOCK, NOW));
		createTradeWithSide(sandboxOnlySeller, sandboxOnlyAccount, sandboxInstrument, null, OrderSide.SELL);

		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		List<Long> stockAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, Market.STOCK);

		assertThat(stockAccountIds)
			.contains(ownerAccount.getId())
			.doesNotContain(sandboxOnlyAccount.getId());
	}

	@Test
	@DisplayName("샌드박스 종목만 매도한 계좌는 existsBy... 판정 둘 다 매도 이력 없는 계좌와 동일하게 false다")
	void existsByMethodsTreatSandboxOnlySellAccountAsNoSellHistory() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		User sandboxOnlySeller = userRepository.saveAndFlush(
			User.create("sandbox-only-exists@finplay.com", "hash", "sandboxexists", NOW));
		Account sandboxOnlyAccount = accountRepository.saveAndFlush(
			Account.create(sandboxOnlySeller, Market.STOCK, NOW));
		createTradeWithSide(sandboxOnlySeller, sandboxOnlyAccount, sandboxInstrument, null, OrderSide.SELL);

		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			sandboxOnlyAccount.getId(), OrderSide.SELL)).isFalse();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			ownerAccount.getId(), OrderSide.SELL)).isTrue();
		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, Market.STOCK)).isTrue();
	}

	@Test
	@DisplayName("buyTrade가 귀속된 order의 practicePriceSessionId를 프로젝션한다")
	void findPracticePriceSessionIdByTradeIdReturnsSessionIdWhenOrderIsPracticeSessionScoped() {
		Instrument crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "PRC01", "테스트프랙티스코인", new BigDecimal("0.00000001"), 5_000L, true, NOW));
		PracticePriceSession session = practicePriceSessionRepository.saveAndFlush(
			PracticePriceSession.create(owner.getId(), crypto.getId(), 1L, (short)1, new BigDecimal("100"), NOW));
		Order practiceOrder = orderRepository.saveAndFlush(Order.createPracticeLimitPendingBuy(
			owner, ownerAccount, crypto, BigDecimal.ONE, new BigDecimal("100"), session.getId(),
			"practice-session-idem", "p".repeat(64), NOW));
		Trade practiceTrade = tradeRepository.saveAndFlush(Trade.of(
			practiceOrder, ownerAccount, crypto, null, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.ONE, 100L, 0L, null, NOW, NOW));

		Optional<Long> result = tradeRepository.findPracticePriceSessionIdByTradeId(practiceTrade.getId());

		assertThat(result).contains(session.getId());
	}

	@Test
	@DisplayName("세션 없는 실제 가격 buyTrade는 practicePriceSessionId 프로젝션이 빈 값이다")
	void findPracticePriceSessionIdByTradeIdReturnsEmptyWhenOrderHasNoPracticeSession() {
		Order order = createOrder(owner, ownerAccount, NOW);
		Trade trade = createTrade(order, ownerAccount, NOW);

		Optional<Long> result = tradeRepository.findPracticePriceSessionIdByTradeId(trade.getId());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("시장별로 매도 이력 계좌 존재 여부를 판정한다(account.market 중첩 탐색)")
	void existsBySideAndAccountMarketDetectsSellHistoryPerMarket() {
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		Account ownerCryptoAccount = accountRepository.saveAndFlush(
			Account.create(owner, Market.CRYPTO, NOW));
		Instrument crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "RBLDX", "재구성존재테스트코인", new BigDecimal("0.00000001"), 5_000L, true, NOW));
		createTradeWithSide(owner, ownerCryptoAccount, crypto, null, OrderSide.SELL);

		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, Market.STOCK)).isTrue();
		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, Market.CRYPTO)).isTrue();
	}

	@Test
	@DisplayName("현재 실행 세대의 체결을 주문 id·방향·유형으로 프로젝션한다")
	void findPracticeRunFillKindsProjectsSideAndOrderTypePerOrder() {
		Instrument sample = tutorialSampleInstrument("FKD01");
		Order marketBuy = practiceOrder(sample, OrderSide.BUY, OrderType.MARKET, 1L);
		practiceFill(marketBuy, sample, OrderSide.BUY);
		Order limitSell = practiceOrder(sample, OrderSide.SELL, OrderType.LIMIT, 1L);
		limitSell.markFilled();
		orderRepository.saveAndFlush(limitSell);
		practiceFill(limitSell, sample, OrderSide.SELL);

		List<PracticeRunFillKindDto> kinds = tradeRepository.findPracticeRunFillKinds(practiceAttemptId, 1L);

		assertThat(kinds).containsExactlyInAnyOrder(
			new PracticeRunFillKindDto(marketBuy.getId(), OrderSide.BUY, OrderType.MARKET),
			new PracticeRunFillKindDto(limitSell.getId(), OrderSide.SELL, OrderType.LIMIT));
	}

	@Test
	@DisplayName("다른 실행 세대와 PENDING 주문은 프로젝션에서 빠진다")
	void findPracticeRunFillKindsExcludesOtherRunsAndPendingOrders() {
		Instrument sample = tutorialSampleInstrument("FKD02");
		Order previousRun = practiceOrder(sample, OrderSide.SELL, OrderType.MARKET, 1L);
		practiceFill(previousRun, sample, OrderSide.SELL);
		Order pendingThisRun = orderRepository.saveAndFlush(Order.createLimitPendingForPracticeAttempt(
			owner, ownerAccount, sample, OrderSide.BUY, BigDecimal.ONE, new BigDecimal("100"),
			practiceAttemptId, 2L, nextIdempotencyKey(), nextRequestHash(), NOW));
		practiceFill(pendingThisRun, sample, OrderSide.BUY);

		List<PracticeRunFillKindDto> kinds = tradeRepository.findPracticeRunFillKinds(practiceAttemptId, 2L);

		assertThat(kinds).isEmpty();
		assertThat(tradeRepository.findPracticeRunFillKinds(practiceAttemptId, 1L))
			.containsExactly(new PracticeRunFillKindDto(previousRun.getId(), OrderSide.SELL, OrderType.MARKET));
	}

	private Instrument tutorialSampleInstrument(String code) {
		Instrument sample = Instrument.create(
			Market.STOCK, code, "튜토리얼 샘플", new BigDecimal("0.00000001"), 0L, true, NOW);
		ReflectionTestUtils.setField(sample, "tutorialSample", true);
		return instrumentRepository.saveAndFlush(sample);
	}

	private Order practiceOrder(Instrument tradedInstrument, OrderSide side, OrderType orderType, long runNumber) {
		if (orderType == OrderType.MARKET) {
			return orderRepository.saveAndFlush(Order.createForPracticeAttempt(
				owner, ownerAccount, tradedInstrument, side, OrderType.MARKET, BigDecimal.ONE,
				practiceAttemptId, runNumber, nextIdempotencyKey(), nextRequestHash(), NOW));
		}
		return orderRepository.saveAndFlush(Order.createLimitPendingForPracticeAttempt(
			owner, ownerAccount, tradedInstrument, side, BigDecimal.ONE, new BigDecimal("100"),
			practiceAttemptId, runNumber, nextIdempotencyKey(), nextRequestHash(), NOW));
	}

	private Trade practiceFill(Order order, Instrument tradedInstrument, OrderSide side) {
		return tradeRepository.saveAndFlush(Trade.of(
			order, ownerAccount, tradedInstrument, null, side,
			BigDecimal.valueOf(100), BigDecimal.ONE, 100L, 0L, side == OrderSide.SELL ? 0L : null, NOW, NOW));
	}

	private String nextIdempotencyKey() {
		idempotencySequence++;
		return "practice-kind-" + idempotencySequence;
	}

	private String nextRequestHash() {
		return String.format("%064d", idempotencySequence);
	}
}
