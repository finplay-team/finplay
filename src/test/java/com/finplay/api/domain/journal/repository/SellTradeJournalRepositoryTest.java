package com.finplay.api.domain.journal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.journal.entity.SellTradeJournal;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SellTradeJournalRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 15, 20, 0);
	private static final String CONTENT = "목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.";

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
	private SellTradeJournalRepository sellTradeJournalRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private EntityManager entityManager;

	private User user;
	private Account account;
	private Instrument instrument;
	private StockReplaySession session;
	private int sequence = 0;

	@BeforeEach
	void setUp() {
		user = userRepository
			.saveAndFlush(User.create("sell-journal-owner@finplay.com", "hash", "selljournalowner", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "SJR01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(20), NOW.toLocalDate(), NOW, NOW));
	}

	private Trade createSellTrade() {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			user,
			account,
			instrument,
			OrderSide.SELL,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"sell-journal-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			account,
			instrument,
			session,
			OrderSide.SELL,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}

	private Trade createSellTradeWithInstrument(Instrument tradeInstrument) {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			user,
			account,
			tradeInstrument,
			OrderSide.SELL,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"sell-journal-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			account,
			tradeInstrument,
			session,
			OrderSide.SELL,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}

	private Trade createSellTradeFor(User tradeUser, Account tradeAccount) {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			tradeUser,
			tradeAccount,
			instrument,
			OrderSide.SELL,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"sell-journal-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			tradeAccount,
			instrument,
			session,
			OrderSide.SELL,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}

	@Test
	@DisplayName("같은 매도 체결에 매도 회고 2건째는 유니크 제약에 걸린다")
	void databaseRejectsSecondJournalForTheSameSellTrade() {
		Trade sellTrade = createSellTrade();
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));

		SellTradeJournal duplicate = SellTradeJournal.of(sellTrade, "다른 내용의 회고.", NOW);

		assertThatThrownBy(() -> sellTradeJournalRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("서로 다른 매도 체결의 매도 회고는 각각 공존한다")
	void journalsForDifferentSellTradesCoexist() {
		Trade firstTrade = createSellTrade();
		Trade secondTrade = createSellTrade();

		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(firstTrade, CONTENT, NOW));
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(secondTrade, CONTENT, NOW));

		assertThat(sellTradeJournalRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("매도 회고를 저장하기 전에는 존재하지 않고, 저장한 뒤에는 존재한다")
	void existsBySellTradeIdTogglesFromFalseToTrueAfterSave() {
		Trade sellTrade = createSellTrade();

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isFalse();

		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isTrue();
	}

	@Test
	@DisplayName("존재하지 않는 체결 ID를 참조하는 매도 회고는 외래키 제약에 걸린다")
	void databaseRejectsJournalReferencingNonExistentTrade() {
		long nonExistentTradeId = 999_999_999L;
		Trade danglingReference = entityManager.getReference(Trade.class, nonExistentTradeId);
		SellTradeJournal journal = SellTradeJournal.of(danglingReference, CONTENT, NOW);

		assertThatThrownBy(() -> sellTradeJournalRepository.saveAndFlush(journal))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("매도 회고가 없는 체결은 findBySellTradeId가 empty를 반환한다")
	void findBySellTradeIdReturnsEmptyWhenJournalDoesNotExist() {
		Trade sellTrade = createSellTrade();

		assertThat(sellTradeJournalRepository.findBySellTradeId(sellTrade.getId())).isEmpty();
	}

	@Test
	@DisplayName("매도 회고가 있는 체결은 findBySellTradeId가 값을 반환한다")
	void findBySellTradeIdReturnsJournalWhenItExists() {
		Trade sellTrade = createSellTrade();
		SellTradeJournal saved = sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));

		assertThat(sellTradeJournalRepository.findBySellTradeId(sellTrade.getId()))
			.isPresent()
			.get()
			.extracting(SellTradeJournal::getId)
			.isEqualTo(saved.getId());
	}

	@Test
	@DisplayName("updateContent 호출 후 flush하면 content와 updated_at만 바뀌고 created_at·sell_trade_id·id는 그대로다")
	void updateContentChangesOnlyContentAndUpdatedAt() {
		Trade sellTrade = createSellTrade();
		SellTradeJournal saved = sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));
		entityManager.clear();

		LocalDateTime updatedAt = NOW.plusDays(1);
		String newContent = "수정된 회고 내용. 손절 기준을 더 명확히 세워야겠다.";

		SellTradeJournal toUpdate = sellTradeJournalRepository.findById(saved.getId()).orElseThrow();
		toUpdate.updateContent(newContent, updatedAt);
		sellTradeJournalRepository.saveAndFlush(toUpdate);
		entityManager.clear();

		SellTradeJournal reloaded = sellTradeJournalRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getId()).isEqualTo(saved.getId());
		assertThat(reloaded.getSellTrade().getId()).isEqualTo(sellTrade.getId());
		assertThat(reloaded.getContent()).isEqualTo(newContent);
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
		assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("커서 조회는 다른 계좌의 매도 회고를 포함하지 않는다")
	void findByAccountIdWithCursorExcludesOtherAccountJournals() {
		Trade ownerTrade = createSellTrade();
		SellTradeJournal ownerJournal = sellTradeJournalRepository.saveAndFlush(
			SellTradeJournal.of(ownerTrade, CONTENT, NOW));

		User other = userRepository
			.saveAndFlush(User.create("sell-journal-other@finplay.com", "hash", "selljournalother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, Market.STOCK, NOW));
		Trade otherTrade = createSellTradeFor(other, otherAccount);
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(otherTrade, CONTENT, NOW));

		List<SellTradeJournal> result = sellTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null,
			null, 10);

		assertThat(result).extracting(SellTradeJournal::getId).containsExactly(ownerJournal.getId());
	}

	@Test
	@DisplayName("커서보다 이전(createdAt이 더 작거나 같은 createdAt에서 체결 ID가 더 작은) 항목만 반환한다")
	void findByAccountIdWithCursorReturnsOnlyItemsBeforeCursor() {
		Trade olderTrade = createSellTrade();
		SellTradeJournal older = sellTradeJournalRepository
			.saveAndFlush(SellTradeJournal.of(olderTrade, CONTENT, NOW.minusMinutes(10)));
		Trade newerTrade = createSellTrade();
		SellTradeJournal newer = sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(newerTrade, CONTENT, NOW));

		List<SellTradeJournal> result = sellTradeJournalRepository.findByAccountIdWithCursor(
			account.getId(), newer.getCreatedAt(), newerTrade.getId(), 10);

		assertThat(result).extracting(SellTradeJournal::getId).containsExactly(older.getId());
	}

	@Test
	@DisplayName("createdAt이 같으면 체결 ID 내림차순으로 정렬해 반환한다")
	void findByAccountIdWithCursorSortedByCreatedAtThenTradeIdDescendingOnTie() {
		Trade firstTrade = createSellTrade();
		SellTradeJournal first = sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(firstTrade, CONTENT, NOW));
		Trade secondTrade = createSellTrade();
		SellTradeJournal second = sellTradeJournalRepository
			.saveAndFlush(SellTradeJournal.of(secondTrade, CONTENT, NOW));
		Trade thirdTrade = createSellTrade();
		SellTradeJournal third = sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(thirdTrade, CONTENT, NOW));

		List<SellTradeJournal> result = sellTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null,
			null, 10);

		assertThat(result).extracting(SellTradeJournal::getId)
			.containsExactly(third.getId(), second.getId(), first.getId());
	}

	@Test
	@DisplayName("튜토리얼 샌드박스 종목 매도 체결의 회고는 커서 조회 결과에서 제외된다")
	void findByAccountIdWithCursorExcludesTutorialSampleInstrumentJournals() {
		Trade realTrade = createSellTrade();
		SellTradeJournal realJournal = sellTradeJournalRepository.saveAndFlush(
			SellTradeJournal.of(realTrade, CONTENT, NOW));

		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();
		Trade sandboxTrade = createSellTradeWithInstrument(sandboxInstrument);
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sandboxTrade, CONTENT, NOW));

		List<SellTradeJournal> result = sellTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null,
			null, 10);

		assertThat(result).extracting(SellTradeJournal::getId).containsExactly(realJournal.getId());
	}

	@Test
	@DisplayName("fetchSize로 지정한 개수만큼만 반환한다")
	void findByAccountIdWithCursorRespectsFetchSize() {
		List<SellTradeJournal> saved = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Trade trade = createSellTrade();
			saved.add(sellTradeJournalRepository.saveAndFlush(
				SellTradeJournal.of(trade, CONTENT, NOW.minusMinutes(i))));
		}

		List<SellTradeJournal> result = sellTradeJournalRepository.findByAccountIdWithCursor(account.getId(), null,
			null, 3);

		assertThat(result).hasSize(3);
		assertThat(result).extracting(SellTradeJournal::getId)
			.containsExactly(saved.get(0).getId(), saved.get(1).getId(), saved.get(2).getId());
	}
}
