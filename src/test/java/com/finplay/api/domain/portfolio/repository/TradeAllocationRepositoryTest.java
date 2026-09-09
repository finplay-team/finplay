package com.finplay.api.domain.portfolio.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
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
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
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
class TradeAllocationRepositoryTest {

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
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	private Account account;
	private Instrument instrument;
	private StockReplaySession session;

	@BeforeEach
	void setUp() {
		User user = userRepository.saveAndFlush(User.create("trader@finplay.com", "hash", "trader", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(33), NOW.toLocalDate(), NOW, NOW));
	}

	@Test
	@DisplayName("배분 기록이 없는 lot의 합계는 0이다")
	void sumsReturnZeroWhenNoAllocationsExist() {
		HoldingLot lot = createLot(BigDecimal.valueOf(10));

		assertThat(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(lot.getId())).isZero();
		assertThat(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(lot.getId())).isZero();
	}

	@Test
	@DisplayName("한 lot에 배분 기록이 여러 건이면 원가·수수료가 각각 합산된다")
	void sumsAccumulateAcrossMultipleAllocationsForSameLot() {
		HoldingLot lot = createLot(BigDecimal.valueOf(10));
		Trade firstSell = createSellTrade(BigDecimal.valueOf(4));
		Trade secondSell = createSellTrade(BigDecimal.valueOf(6));

		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(firstSell, lot, BigDecimal.valueOf(4), 280_000L, 40L, NOW));
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(secondSell, lot, BigDecimal.valueOf(6), 420_000L, 60L, NOW));

		assertThat(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(lot.getId())).isEqualTo(700_000L);
		assertThat(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(lot.getId())).isEqualTo(100L);
	}

	@Test
	@DisplayName("같은 매도 체결이 같은 lot에 두 번 배분되면 유니크 제약 위반이다")
	void rejectsDuplicateAllocationForSameSellTradeAndHoldingLot() {
		HoldingLot lot = createLot(BigDecimal.valueOf(10));
		Trade sellTrade = createSellTrade(BigDecimal.valueOf(10));
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, BigDecimal.valueOf(6), 420_000L, 60L, NOW));

		assertThatThrownBy(() -> tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, BigDecimal.valueOf(4), 280_000L, 40L, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private HoldingLot createLot(BigDecimal quantity) {
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-" + System.nanoTime(), "a".repeat(64), NOW));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, NOW, NOW));
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, NOW, NOW));
	}

	private Trade createSellTrade(BigDecimal quantity) {
		Order sellOrder = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET,
			quantity, "idem-sell-" + System.nanoTime(), "b".repeat(64), NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			sellOrder, account, instrument, session, OrderSide.SELL,
			BigDecimal.valueOf(75000), quantity,
			75000L * quantity.longValueExact(), 100L, 49_900L, NOW, NOW));
	}
}
