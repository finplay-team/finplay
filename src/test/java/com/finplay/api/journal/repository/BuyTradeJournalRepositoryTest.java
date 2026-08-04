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
import com.finplay.api.market.repository.InstrumentRepository;
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
	private BuyTradeJournalRepository buyTradeJournalRepository;

	@Autowired
	private EntityManager entityManager;

	private User user;
	private Account account;
	private Instrument instrument;
	private int sequence = 0;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("journal-owner@finplay.com", "hash", "journalowner", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
		// V7 시드와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "JRN01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
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
}
