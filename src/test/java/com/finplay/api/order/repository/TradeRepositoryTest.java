// 주문별 체결 단건 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (docs/specs/004-order-buy 이슈 #22)
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
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

	private User owner;
	private Account ownerAccount;
	private Instrument instrument;
	private int idempotencySequence = 0;

	private Order createOrder(User user, Account account, LocalDateTime requestedAt) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "cursor-idem-" + idempotencySequence,
			String.valueOf(hashChar).repeat(64), requestedAt));
	}

	private Trade createTrade(Order order, Account account, LocalDateTime executedAt) {
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L, null, executedAt, executedAt));
	}

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("trade-owner@finplay.com", "hash", "tradeowner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TRD01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	@Test
	@DisplayName("주문 ID로 체결을 조회한다")
	void findsTradeByOrderIdWhenExists() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-1", "j".repeat(64), NOW));
		Trade trade = tradeRepository.saveAndFlush(Trade.of(
			order, ownerAccount, instrument, OrderSide.BUY,
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
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다")
	void findByAccountIdWithCursorFetchesInstrumentWithoutLazyInitException() {
		Order order = createOrder(owner, ownerAccount, NOW);
		createTrade(order, ownerAccount, NOW);
		entityManager.clear();

		List<Trade> result = tradeRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(trade -> trade.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}
}
