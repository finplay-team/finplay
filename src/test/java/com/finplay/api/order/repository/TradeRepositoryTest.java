// 주문별 체결 단건 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (ai/specs/004-order-buy 이슈 #22)
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeRunFillKindDto;
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
	// orders.practice_attempt_id는 practice_attempts를 가리키는 FK다 — 임의의 숫자를 쓰면 insert가 막힌다.
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

	// 랭킹 재구성 조회(이슈 #279)용 픽스처 — 임의의 시장/방향 조합으로 주문+체결을 만든다.
	// 매도 체결의 realizedPnl을 일부러 0으로 둔다: 대상 판정 기준은 trades.side = 'SELL'이며
	// accounts.realized_pnl != 0이 아니다(매도했지만 손익이 정확히 0인 계좌가 누락되면 안 된다).
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
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
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
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));

		Order ownerOrder = createOrder(owner, ownerAccount, NOW);
		Trade ownerTrade = createTrade(ownerOrder, ownerAccount, NOW);
		Order otherOrder = createOrder(other, otherAccount, NOW);
		createTrade(otherOrder, otherAccount, NOW);

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Trade::getId).containsExactly(ownerTrade.getId());
	}

	// 포트폴리오 체결 내역 화면에 튜토리얼 샌드박스 종목 체결이 섞여 나오던 누출 수정 —
	// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001)와 동일 원칙을 GET /api/trades에도 적용한다.
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

	// 아래 3개는 랭킹 재구성(이슈 #279)이 쓰는 매도 이력 조회다.
	// 단정을 containsExactly가 아니라 포함/미포함으로 쓰는 이유: 이 저장소에는 비-@Transactional
	// @SpringBootTest가 공유 MySQL 컨테이너에 매도 체결을 커밋한 채 남긴다. 전체 개수를 단정하면 실행 순서에
	// 따라 깨진다 (ai/agent-mistakes.md 2026-08-04 "공유 컨테이너 커밋" 행).
	// 알려진 오염원은 LimitOrderConcurrencyIntegrationTest·OrderSellIntegrationTest다(둘 다 order 도메인의
	// 비-@Transactional 통합 테스트). RankingIntegrationTest·RankingRebuildIntegrationTest는 tearDown에서
	// 자기 원장을 지우게 되어(이슈 #279) 오염원에서 빠졌다. 잔재 개수는 테스트가 늘 때마다 바뀌므로 여기
	// 적지 않는다 — 이 목록은 "어디를 봐야 하는지"의 단서일 뿐 단정의 근거가 아니다.
	@Test
	@DisplayName("매도 이력 계좌 id를 중복 없이, 요청한 시장으로 한정해, 매수만 있는 계좌는 빼고 조회한다")
	void findDistinctAccountIdsBySideAndMarketDeduplicatesAndScopesByMarket() {
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);
		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		User buyer = userRepository.saveAndFlush(
			User.create("rebuild-buyer@finplay.com", "hash", "rebuildbuyer", NOW));
		Account buyOnlyAccount = accountRepository.saveAndFlush(
			Account.create(buyer, com.finplay.api.account.domain.Market.STOCK, NOW));
		createTradeWithSide(buyer, buyOnlyAccount, instrument, session, OrderSide.BUY);

		Account ownerCryptoAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		Instrument crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "RBLDC", "재구성테스트코인", new BigDecimal("0.00000001"), 5_000L, true, NOW));
		createTradeWithSide(owner, ownerCryptoAccount, crypto, null, OrderSide.SELL);

		List<Long> stockAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, com.finplay.api.account.domain.Market.STOCK);
		List<Long> cryptoAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, com.finplay.api.account.domain.Market.CRYPTO);

		assertThat(stockAccountIds)
			.doesNotHaveDuplicates()
			.contains(ownerAccount.getId())
			.doesNotContain(buyOnlyAccount.getId(), ownerCryptoAccount.getId());
		assertThat(cryptoAccountIds)
			.doesNotHaveDuplicates()
			.contains(ownerCryptoAccount.getId())
			.doesNotContain(ownerAccount.getId());
	}

	// 이번 정책의 존재 이유(spec.md 비즈니스 규칙)를 양방향으로 못 박는다. 대상 판정 기준은 trades.side = 'SELL'이지
	// accounts.realized_pnl != 0이 아니다.
	//  - 매도했는데 손익이 정확히 0인 계좌를 빼면 재구성 결과가 유실 전과 달라진다.
	//  - 매도 이력이 없는데 realized_pnl만 0이 아닌 계좌를 넣으면 원장에 근거가 없는 유령 계좌가 랭킹에 뜬다.
	// 픽스처가 실제로 그 경계값인지(0 / 0 아님)를 먼저 단정한다 — 그 단정이 없으면 아래 contains·doesNotContain이
	// 무엇을 증명하는지 알 수 없고, 나중에 픽스처 값만 바뀌어도 회귀를 놓친다.
	@Test
	@DisplayName("realized_pnl이 0이어도 매도 이력이 있으면 포함하고, realized_pnl이 0이 아니어도 매도 이력이 없으면 제외한다")
	void findDistinctAccountIdsBySideAndMarketKeysOnSellSideNotRealizedPnl() {
		Trade zeroPnlSellTrade = createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		User pnlOnlyUser = userRepository.saveAndFlush(
			User.create("rebuild-pnl-only@finplay.com", "hash", "rebuildpnlonly", NOW));
		Account pnlOnlyAccount = Account.create(pnlOnlyUser, com.finplay.api.account.domain.Market.STOCK, NOW);
		pnlOnlyAccount.addRealizedPnl(123_456L);
		accountRepository.saveAndFlush(pnlOnlyAccount);
		createTradeWithSide(pnlOnlyUser, pnlOnlyAccount, instrument, session, OrderSide.BUY);

		entityManager.flush();
		entityManager.clear();
		Account reloadedSellAccount = accountRepository.findById(ownerAccount.getId()).orElseThrow();
		Account reloadedPnlOnlyAccount = accountRepository.findById(pnlOnlyAccount.getId()).orElseThrow();

		List<Long> accountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, com.finplay.api.account.domain.Market.STOCK);

		assertThat(zeroPnlSellTrade.getRealizedPnl()).isZero();
		assertThat(reloadedSellAccount.getRealizedPnl()).isZero();
		assertThat(reloadedPnlOnlyAccount.getRealizedPnl()).isNotZero();
		assertThat(accountIds)
			.contains(ownerAccount.getId())
			.doesNotContain(pnlOnlyAccount.getId());
	}

	// 같은 경계를 단건 판정에서도 확인한다 — 내 랭킹 status가 realized_pnl로 갈아타면 손익 0인 매도 계좌가
	// 영원히 REBUILDING으로 보인다.
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
			Account.create(buyer, com.finplay.api.account.domain.Market.STOCK, NOW));
		createTradeWithSide(buyer, buyOnlyAccount, instrument, session, OrderSide.BUY);

		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			ownerAccount.getId(), OrderSide.SELL)).isTrue();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			buyOnlyAccount.getId(), OrderSide.SELL)).isFalse();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			buyOnlyAccount.getId(), OrderSide.BUY)).isTrue();
	}

	// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-003): 샌드박스 종목만 매도한 계좌는 대상자 목록·
	// existsBy... 판정 둘 다에서 매도 이력이 아예 없는 계좌와 동일하게 취급돼야 한다.
	@Test
	@DisplayName("샌드박스 종목만 매도한 계좌는 대상자 목록에서 매도 이력 없는 계좌와 동일하게 제외된다")
	void findDistinctAccountIdsBySideAndMarketExcludesSandboxOnlySellAccount() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		User sandboxOnlySeller = userRepository.saveAndFlush(
			User.create("sandbox-only-seller@finplay.com", "hash", "sandboxonly", NOW));
		Account sandboxOnlyAccount = accountRepository.saveAndFlush(
			Account.create(sandboxOnlySeller, com.finplay.api.account.domain.Market.STOCK, NOW));
		createTradeWithSide(sandboxOnlySeller, sandboxOnlyAccount, sandboxInstrument, null, OrderSide.SELL);

		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		List<Long> stockAccountIds = tradeRepository.findDistinctAccountIdsBySideAndMarket(
			OrderSide.SELL, com.finplay.api.account.domain.Market.STOCK);

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
			Account.create(sandboxOnlySeller, com.finplay.api.account.domain.Market.STOCK, NOW));
		createTradeWithSide(sandboxOnlySeller, sandboxOnlyAccount, sandboxInstrument, null, OrderSide.SELL);

		createTradeWithSide(owner, ownerAccount, instrument, session, OrderSide.SELL);

		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			sandboxOnlyAccount.getId(), OrderSide.SELL)).isFalse();
		assertThat(tradeRepository.existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(
			ownerAccount.getId(), OrderSide.SELL)).isTrue();
		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, com.finplay.api.account.domain.Market.STOCK)).isTrue();
	}

	// 시장별 매도 이력 유무는 "있음"만 단정한다 — "없음"은 공유 컨테이너에 남은 다른 클래스의 커밋에 좌우돼
	// 이 슬라이스에서 결정적으로 재현할 수 없다. 시장 한정이 실제로 걸리는지는 위
	// findDistinctAccountIdsBySideAndMarket 테스트의 doesNotContain이 같은 중첩 탐색 경로로 확인한다.
	// 030 holding 관찰 세션 역추적(이슈 #321)이 쓰는 단일 필드 프로젝션 쿼리를 검증한다.
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
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		Instrument crypto = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "RBLDX", "재구성존재테스트코인", new BigDecimal("0.00000001"), 5_000L, true, NOW));
		createTradeWithSide(owner, ownerCryptoAccount, crypto, null, OrderSide.SELL);

		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, com.finplay.api.account.domain.Market.STOCK)).isTrue();
		assertThat(tradeRepository.existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(
			OrderSide.SELL, com.finplay.api.account.domain.Market.CRYPTO)).isTrue();
	}

	// 이슈 #503 — 튜토리얼 5단계 진행 판정이 읽는 생성자 표현식 쿼리. 생성자 표현식은 컴파일이 아니라
	// 실행 시점에 깨지므로 실제 DB로 한 번 돌려 둔다.
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

	// 재시작하면 run 번호가 올라간다. 이전 세대의 체결이 새 세대 판정에 섞이면 재시작해도 단계가
	// 완료로 남는다.
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
		// 양성 대조 — 빈 결과만 보면 where 절이 통째로 아무것도 못 맞추는 회귀에서도 통과한다.
		// 같은 픽스처를 run 1로 조회하면 이전 세대 체결이 그대로 나와야 "배제가 선택적으로 작동한다"가 된다.
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
