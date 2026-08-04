// HoldingLotRepository의 FIFO 미소진 lot 조회 쿼리를 검증하는 슬라이스 테스트다.
package com.finplay.api.portfolio.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
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
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
class HoldingLotRepositoryTest {

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

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
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	private Holding holding;
	private StockReplaySession session;

	@BeforeEach
	void setUp() {
		User user = userRepository.saveAndFlush(User.create("trader@finplay.com", "hash", "trader", BASE));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, BASE));
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, BASE));
		holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, BASE));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(BASE.toLocalDate().plusYears(32), BASE.toLocalDate(), BASE, BASE));
	}

	@Test
	@DisplayName("실행시각 오름차순, 동시각이면 id 오름차순으로 잔여수량 0 초과인 lot만 조회한다")
	void findsUnconsumedLotsOrderedByExecutedAtThenId() {
		HoldingLot exhausted = createLot(BigDecimal.valueOf(5), BASE.minusHours(3));
		exhausted.consume(BigDecimal.valueOf(5));
		holdingLotRepository.saveAndFlush(exhausted);

		HoldingLot second = createLot(BigDecimal.valueOf(3), BASE.minusHours(1));
		HoldingLot firstSameTime = createLot(BigDecimal.valueOf(2), BASE.minusHours(2));
		HoldingLot secondSameTime = createLot(BigDecimal.valueOf(4), BASE.minusHours(2));

		List<HoldingLot> found = holdingLotRepository
			.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
				holding.getId(), BigDecimal.ZERO);

		assertThat(found).extracting(HoldingLot::getId)
			.containsExactly(firstSameTime.getId(), secondSameTime.getId(), second.getId());
	}

	@Test
	@DisplayName("모든 lot이 소진된 경우 빈 목록을 반환한다")
	void returnsEmptyListWhenAllLotsExhausted() {
		HoldingLot exhausted = createLot(BigDecimal.valueOf(5), BASE.minusHours(1));
		exhausted.consume(BigDecimal.valueOf(5));
		holdingLotRepository.saveAndFlush(exhausted);

		List<HoldingLot> found = holdingLotRepository
			.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
				holding.getId(), BigDecimal.ZERO);

		assertThat(found).isEmpty();
	}

	private HoldingLot createLot(BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			holding.getAccount().getUser(), holding.getAccount(), holding.getInstrument(),
			OrderSide.BUY, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, holding.getAccount(), holding.getInstrument(), session, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, executedAt, executedAt));
		return holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, executedAt, executedAt));
	}
}
