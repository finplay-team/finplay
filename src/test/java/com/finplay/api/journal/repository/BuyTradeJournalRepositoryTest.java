// buy_trade_journals의 UNIQUE(buy_trade_id)·FK(trades) 제약과 existsByBuyTradeId 쿼리를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.journal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.journal.domain.BuyTradeJournal;
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
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class BuyTradeJournalRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 10, 0, 0);
	private static final String CONTENT = "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.";

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
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private BuyTradeJournalRepository buyTradeJournalRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private User user;
	private Account account;
	private Instrument instrument;
	private StockReplaySession session;
	private int sequence = 0;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("journal-owner@finplay.com", "hash", "journalowner", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
		// V7 시드와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "JRN01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(31), NOW.toLocalDate(), NOW, NOW));
	}

	private Trade createBuyTrade() {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			user,
			account,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"journal-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			account,
			instrument,
			session,
			OrderSide.BUY,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}

	private Trade createBuyTradeFor(User tradeUser, Account tradeAccount) {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			tradeUser,
			tradeAccount,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"journal-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			tradeAccount,
			instrument,
			session,
			OrderSide.BUY,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}

	// --- ① 같은 buy_trade_id로 2건 저장 시 유니크 위반 ---

	@Test
	@DisplayName("같은 매수 체결에 투자일기 2건째는 유니크 제약에 걸린다")
	void databaseRejectsSecondJournalForTheSameBuyTrade() {
		Trade buyTrade = createBuyTrade();
		buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(buyTrade, CONTENT, NOW));

		BuyTradeJournal duplicate = BuyTradeJournal.of(buyTrade, "다른 내용의 일기.", NOW);

		assertThatThrownBy(() -> buyTradeJournalRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- ② 서로 다른 체결 2건은 공존 ---

	@Test
	@DisplayName("서로 다른 매수 체결의 투자일기는 각각 공존한다")
	void journalsForDifferentBuyTradesCoexist() {
		Trade firstTrade = createBuyTrade();
		Trade secondTrade = createBuyTrade();

		buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(firstTrade, CONTENT, NOW));
		buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(secondTrade, CONTENT, NOW));

		assertThat(buyTradeJournalRepository.count()).isEqualTo(2);
	}

	// --- ③ existsByBuyTradeId가 저장 전후로 false→true ---

	@Test
	@DisplayName("투자일기를 저장하기 전에는 존재하지 않고, 저장한 뒤에는 존재한다")
	void existsByBuyTradeIdTogglesFromFalseToTrueAfterSave() {
		Trade buyTrade = createBuyTrade();

		assertThat(buyTradeJournalRepository.existsByBuyTradeId(buyTrade.getId())).isFalse();

		buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(buyTrade, CONTENT, NOW));

		assertThat(buyTradeJournalRepository.existsByBuyTradeId(buyTrade.getId())).isTrue();
	}

	// --- ④ 존재하지 않는 trades.id를 참조하면 FK 위반 ---

	@Test
	@DisplayName("존재하지 않는 체결 ID를 참조하는 투자일기는 외래키 제약에 걸린다")
	void databaseRejectsJournalReferencingNonExistentTrade() {
		long nonExistentTradeId = 999_999_999L;
		Trade danglingReference = entityManager.getReference(Trade.class, nonExistentTradeId);
		BuyTradeJournal journal = BuyTradeJournal.of(danglingReference, CONTENT, NOW);

		assertThatThrownBy(() -> buyTradeJournalRepository.saveAndFlush(journal))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- ⑤ findByBuyTradeId가 부재 시 empty, 존재 시 값을 반환 ---

	@Test
	@DisplayName("매수 투자일기가 없는 체결은 findByBuyTradeId가 empty를 반환한다")
	void findByBuyTradeIdReturnsEmptyWhenJournalDoesNotExist() {
		Trade buyTrade = createBuyTrade();

		assertThat(buyTradeJournalRepository.findByBuyTradeId(buyTrade.getId())).isEmpty();
	}

	@Test
	@DisplayName("매수 투자일기가 있는 체결은 findByBuyTradeId가 값을 반환한다")
	void findByBuyTradeIdReturnsJournalWhenItExists() {
		Trade buyTrade = createBuyTrade();
		BuyTradeJournal saved = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(buyTrade, CONTENT, NOW));

		assertThat(buyTradeJournalRepository.findByBuyTradeId(buyTrade.getId()))
			.isPresent()
			.get()
			.extracting(BuyTradeJournal::getId)
			.isEqualTo(saved.getId());
	}

	// --- ⑥ updateContent 후 flush하면 content·updated_at만 바뀌고 나머지는 그대로 ---

	@Test
	@DisplayName("updateContent 호출 후 flush하면 content와 updated_at만 바뀌고 created_at·buy_trade_id·id는 그대로다")
	void updateContentChangesOnlyContentAndUpdatedAt() {
		Trade buyTrade = createBuyTrade();
		BuyTradeJournal saved = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(buyTrade, CONTENT, NOW));
		entityManager.clear();

		LocalDateTime updatedAt = NOW.plusDays(1);
		String newContent = "수정된 회고 내용. 매수 타이밍을 더 신중히 잡아야겠다.";

		BuyTradeJournal toUpdate = buyTradeJournalRepository.findById(saved.getId()).orElseThrow();
		toUpdate.updateContent(newContent, updatedAt);
		buyTradeJournalRepository.saveAndFlush(toUpdate);
		entityManager.clear();

		BuyTradeJournal reloaded = buyTradeJournalRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getId()).isEqualTo(saved.getId());
		assertThat(reloaded.getBuyTrade().getId()).isEqualTo(buyTrade.getId());
		assertThat(reloaded.getContent()).isEqualTo(newContent);
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
		assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
	}

	// --- ⑦ updated_at 컬럼의 NOT NULL 제약 ---

	@Test
	@DisplayName("updated_at을 null로 저장하려는 시도는 NOT NULL 제약에 걸린다")
	void databaseRejectsNullUpdatedAt() {
		Trade buyTrade = createBuyTrade();

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into buy_trade_journals (buy_trade_id, content, created_at, updated_at) "
				+ "values (?, ?, ?, null)",
			buyTrade.getId(),
			CONTENT,
			NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- ⑧ findByAccountIdWithCursor: 다른 계좌 회고 미포함 ---

	@Test
	@DisplayName("커서 조회는 다른 계좌의 매수 투자일기를 포함하지 않는다")
	void findByAccountIdWithCursorExcludesOtherAccountJournals() {
		Trade ownerTrade = createBuyTrade();
		BuyTradeJournal ownerJournal = buyTradeJournalRepository.saveAndFlush(
			BuyTradeJournal.of(ownerTrade, CONTENT, NOW));

		User other = userRepository.saveAndFlush(User.create("journal-other@finplay.com", "hash", "journalother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));
		Trade otherTrade = createBuyTradeFor(other, otherAccount);
		buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(otherTrade, CONTENT, NOW));

		List<BuyTradeJournal> result = buyTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null, null,
			10);

		assertThat(result).extracting(BuyTradeJournal::getId).containsExactly(ownerJournal.getId());
	}

	// --- ⑨ 커서 이전 항목만 반환 + createdAt 동점 시 체결 ID 내림차순 ---

	@Test
	@DisplayName("커서보다 이전(createdAt이 더 작거나 같은 createdAt에서 체결 ID가 더 작은) 항목만 반환한다")
	void findByAccountIdWithCursorReturnsOnlyItemsBeforeCursor() {
		Trade olderTrade = createBuyTrade();
		BuyTradeJournal older = buyTradeJournalRepository
			.saveAndFlush(BuyTradeJournal.of(olderTrade, CONTENT, NOW.minusMinutes(10)));
		Trade newerTrade = createBuyTrade();
		BuyTradeJournal newer = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(newerTrade, CONTENT, NOW));

		List<BuyTradeJournal> result = buyTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), newer.getCreatedAt(), newerTrade.getId(), 10);

		assertThat(result).extracting(BuyTradeJournal::getId).containsExactly(older.getId());
	}

	@Test
	@DisplayName("createdAt이 같으면 체결 ID 내림차순으로 정렬해 반환한다")
	void findByAccountIdWithCursorSortedByCreatedAtThenTradeIdDescendingOnTie() {
		Trade firstTrade = createBuyTrade();
		BuyTradeJournal first = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(firstTrade, CONTENT, NOW));
		Trade secondTrade = createBuyTrade();
		BuyTradeJournal second = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(secondTrade, CONTENT, NOW));
		Trade thirdTrade = createBuyTrade();
		BuyTradeJournal third = buyTradeJournalRepository.saveAndFlush(BuyTradeJournal.of(thirdTrade, CONTENT, NOW));

		List<BuyTradeJournal> result = buyTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null, null,
			10);

		assertThat(result).extracting(BuyTradeJournal::getId)
			.containsExactly(third.getId(), second.getId(), first.getId());
	}

	// --- ⑩ fetchSize(limit) 준수 ---

	@Test
	@DisplayName("fetchSize로 지정한 개수만큼만 반환한다")
	void findByAccountIdWithCursorRespectsFetchSize() {
		List<BuyTradeJournal> saved = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Trade trade = createBuyTrade();
			saved.add(buyTradeJournalRepository.saveAndFlush(
				BuyTradeJournal.of(trade, CONTENT, NOW.minusMinutes(i))));
		}

		List<BuyTradeJournal> result = buyTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null, null,
			3);

		assertThat(result).hasSize(3);
		assertThat(result).extracting(BuyTradeJournal::getId)
			.containsExactly(saved.get(0).getId(), saved.get(1).getId(), saved.get(2).getId());
	}
}
