// sell_trade_journals의 UNIQUE(sell_trade_id)·FK(trades) 제약과 existsBySellTradeId 쿼리를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.journal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.journal.domain.SellTradeJournal;
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
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
		// V7 시드와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
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

	// --- ① 같은 sell_trade_id로 2건 저장 시 유니크 위반 ---

	@Test
	@DisplayName("같은 매도 체결에 매도 회고 2건째는 유니크 제약에 걸린다")
	void databaseRejectsSecondJournalForTheSameSellTrade() {
		Trade sellTrade = createSellTrade();
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));

		SellTradeJournal duplicate = SellTradeJournal.of(sellTrade, "다른 내용의 회고.", NOW);

		assertThatThrownBy(() -> sellTradeJournalRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- ② 서로 다른 체결 2건은 공존 ---

	@Test
	@DisplayName("서로 다른 매도 체결의 매도 회고는 각각 공존한다")
	void journalsForDifferentSellTradesCoexist() {
		Trade firstTrade = createSellTrade();
		Trade secondTrade = createSellTrade();

		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(firstTrade, CONTENT, NOW));
		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(secondTrade, CONTENT, NOW));

		assertThat(sellTradeJournalRepository.count()).isEqualTo(2);
	}

	// --- ③ existsBySellTradeId가 저장 전후로 false→true ---

	@Test
	@DisplayName("매도 회고를 저장하기 전에는 존재하지 않고, 저장한 뒤에는 존재한다")
	void existsBySellTradeIdTogglesFromFalseToTrueAfterSave() {
		Trade sellTrade = createSellTrade();

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isFalse();

		sellTradeJournalRepository.saveAndFlush(SellTradeJournal.of(sellTrade, CONTENT, NOW));

		assertThat(sellTradeJournalRepository.existsBySellTradeId(sellTrade.getId())).isTrue();
	}

	// --- ④ 존재하지 않는 trades.id를 참조하면 FK 위반 ---

	@Test
	@DisplayName("존재하지 않는 체결 ID를 참조하는 매도 회고는 외래키 제약에 걸린다")
	void databaseRejectsJournalReferencingNonExistentTrade() {
		long nonExistentTradeId = 999_999_999L;
		Trade danglingReference = entityManager.getReference(Trade.class, nonExistentTradeId);
		SellTradeJournal journal = SellTradeJournal.of(danglingReference, CONTENT, NOW);

		assertThatThrownBy(() -> sellTradeJournalRepository.saveAndFlush(journal))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- ⑤ findBySellTradeId가 부재 시 empty, 존재 시 값을 반환 ---

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

	// --- ⑥ updateContent 후 flush하면 content·updated_at만 바뀌고 나머지는 그대로 ---

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
}
