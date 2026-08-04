// 주문·체결 원장 5개 엔티티(Order·Trade·Holding·HoldingLot·TradeAllocation)의 스키마 매핑과
// 유니크 제약 2종을 검증하는 슬라이스 테스트 (ADR-0003, docs/specs/011-order-ledger-schema)
package com.finplay.api.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
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
class OrderLedgerSchemaTest {

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
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	private User user;
	private Account account;
	private Instrument instrument;
	private StockReplaySession session;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("trader@finplay.com", "hash", "trader", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(34), NOW.toLocalDate(), NOW, NOW));
	}

	@Test
	@DisplayName("Order를 저장하고 조회하면 멱등키·요청해시를 포함한 모든 필드가 그대로 유지된다")
	void orderSavesAndLoadsWithAllFields() {
		Order saved = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key-1", "a".repeat(64), NOW));

		Order found = orderRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getAccount().getId()).isEqualTo(account.getId());
		assertThat(found.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(found.getSide()).isEqualTo(OrderSide.BUY);
		assertThat(found.getOrderType()).isEqualTo(OrderType.MARKET);
		assertThat(found.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(found.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(found.getIdempotencyKey()).isEqualTo("idem-key-1");
		assertThat(found.getRequestHash()).isEqualTo("a".repeat(64));
		assertThat(found.getRequestedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 회원이 같은 idempotencyKey로 두 번 주문하면 UNIQUE(user_id, idempotency_key) 위반 예외가 발생한다")
	void duplicateIdempotencyKeyForSameUserViolatesUniqueConstraint() {
		orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "dup-key", "a".repeat(64), NOW));

		Order duplicate = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(5), "dup-key", "b".repeat(64), NOW);

		assertThatThrownBy(() -> orderRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Trade를 저장하고 조회하면 체결가·수량·수수료 등 모든 필드가 그대로 유지된다")
	void tradeSavesAndLoadsWithAllFields() {
		Order order = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-1", "c".repeat(64), NOW));

		Trade saved = tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), BigDecimal.valueOf(10),
			700_000L, 100L, null, NOW, NOW));

		Trade found = tradeRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getOrder().getId()).isEqualTo(order.getId());
		assertThat(found.getAccount().getId()).isEqualTo(account.getId());
		assertThat(found.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(found.getSide()).isEqualTo(OrderSide.BUY);
		assertThat(found.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(70000));
		assertThat(found.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(found.getAmount()).isEqualTo(700_000L);
		assertThat(found.getFee()).isEqualTo(100L);
		assertThat(found.getRealizedPnl()).isNull();
		assertThat(found.getExecutedAt()).isEqualTo(NOW);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 order_id로 Trade를 두 번 저장하면 UNIQUE(trades.order_id) 위반 예외가 발생한다")
	void duplicateOrderIdForTradeViolatesUniqueConstraint() {
		Order order = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-uk-idem-1", "g".repeat(64), NOW));
		tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), BigDecimal.valueOf(10),
			700_000L, 100L, null, NOW, NOW));

		Trade duplicate = Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(71000), BigDecimal.valueOf(5),
			355_000L, 50L, null, NOW, NOW);

		assertThatThrownBy(() -> tradeRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("Holding을 저장하고 조회하면 보유수량·평균단가·활성여부가 그대로 유지된다")
	void holdingSavesAndLoadsWithAllFields() {
		Holding saved = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));

		Holding found = holdingRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getAccount().getId()).isEqualTo(account.getId());
		assertThat(found.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(found.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(found.getAveragePrice()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(found.isActive()).isFalse();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 계좌·종목 조합으로 Holding을 두 번 저장하면 UNIQUE(account_id, instrument_id) 위반 예외가 발생한다")
	void duplicateAccountInstrumentViolatesUniqueConstraint() {
		holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));

		Holding duplicate = Holding.create(account, instrument, NOW);

		assertThatThrownBy(() -> holdingRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("HoldingLot을 저장하고 조회하면 최초수량·잔여수량·매수단가가 그대로 유지된다")
	void holdingLotSavesAndLoadsWithAllFields() {
		Order order = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "lot-idem-1", "d".repeat(64), NOW));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), BigDecimal.valueOf(10),
			700_000L, 100L, null, NOW, NOW));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));

		HoldingLot saved = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, BigDecimal.valueOf(10), BigDecimal.valueOf(70000), 100L, NOW, NOW));

		HoldingLot found = holdingLotRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getHolding().getId()).isEqualTo(holding.getId());
		assertThat(found.getBuyTrade().getId()).isEqualTo(buyTrade.getId());
		assertThat(found.getOriginalQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(found.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(found.getUnitCost()).isEqualByComparingTo(BigDecimal.valueOf(70000));
		assertThat(found.getBuyFee()).isEqualTo(100L);
		assertThat(found.getExecutedAt()).isEqualTo(NOW);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 buy_trade_id로 HoldingLot을 두 번 저장하면 UNIQUE(holding_lots.buy_trade_id) 위반 예외가 발생한다")
	void duplicateBuyTradeIdForHoldingLotViolatesUniqueConstraint() {
		Order order = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "lot-uk-idem-1", "h".repeat(64), NOW));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), BigDecimal.valueOf(10),
			700_000L, 100L, null, NOW, NOW));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, BigDecimal.valueOf(10), BigDecimal.valueOf(70000), 100L, NOW, NOW));

		HoldingLot duplicate = HoldingLot.create(
			holding, buyTrade, BigDecimal.valueOf(10), BigDecimal.valueOf(70000), 100L, NOW, NOW);

		assertThatThrownBy(() -> holdingLotRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("TradeAllocation을 저장하고 조회하면 배분수량·배분원가·배분수수료가 그대로 유지된다")
	void tradeAllocationSavesAndLoadsWithAllFields() {
		Order buyOrder = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "alloc-buy-idem", "e".repeat(64), NOW));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			buyOrder, account, instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), BigDecimal.valueOf(10),
			700_000L, 100L, null, NOW, NOW));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, BigDecimal.valueOf(10), BigDecimal.valueOf(70000), 100L, NOW, NOW));

		Order sellOrder = orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(10), "alloc-sell-idem", "f".repeat(64), NOW));
		Trade sellTrade = tradeRepository.saveAndFlush(Trade.of(
			sellOrder, account, instrument, session, OrderSide.SELL,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, 49_900L, NOW, NOW));

		TradeAllocation saved = tradeAllocationRepository.saveAndFlush(TradeAllocation.create(
			sellTrade, lot, BigDecimal.valueOf(10), 700_000L, 100L, NOW));

		TradeAllocation found = tradeAllocationRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getSellTrade().getId()).isEqualTo(sellTrade.getId());
		assertThat(found.getHoldingLot().getId()).isEqualTo(lot.getId());
		assertThat(found.getAllocatedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(found.getAllocatedCost()).isEqualTo(700_000L);
		assertThat(found.getAllocatedBuyFee()).isEqualTo(100L);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}
}
