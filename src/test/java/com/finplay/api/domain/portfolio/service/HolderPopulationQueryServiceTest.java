package com.finplay.api.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.lang.reflect.Method;
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
@Import({TestcontainersConfiguration.class, HolderPopulationQueryService.class})
class HolderPopulationQueryServiceTest {

	private static final LocalDateTime T = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

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

	@Autowired
	private HolderPopulationQueryService holderPopulationQueryService;

	private Instrument instrument;
	private StockReplaySession session;
	private int memberSeq = 0;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, T));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(T.toLocalDate().plusYears(30), T.toLocalDate(), T, T));
	}

	@Test
	@DisplayName("T 시점엔 보유 중이었으나 T 이후 전량 매도해 지금 holdings에는 없는 회원도 모집단에 포함된다")
	void includesMemberWhoFullySoldAfterT() {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
		Trade sellAfterT = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), T.plusHours(1));
		allocate(sellAfterT, lot, BigDecimal.valueOf(10));

		int holderCount = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);

		assertThat(holderCount).isEqualTo(1);
	}

	@Test
	@DisplayName("T 이전에 이미 전량 매도한 회원은 모집단에서 제외된다")
	void excludesMemberWhoFullySoldBeforeT() {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
		Trade sellBeforeT = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), T.minusHours(1));
		allocate(sellBeforeT, lot, BigDecimal.valueOf(10));

		int holderCount = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);

		assertThat(holderCount).isZero();
	}

	@Test
	@DisplayName("T 이후에 처음 매수한 회원은 T 시점 모집단에 포함되지 않는다")
	void excludesMemberWhoBoughtAfterT() {
		Holding holding = createHolding();
		createBuyLot(holding, BigDecimal.valueOf(10), T.plusHours(1));

		int holderCount = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);

		assertThat(holderCount).isZero();
	}

	@Test
	@DisplayName("포함·매도전제외·매수전제외 세 갈래가 섞인 여러 회원의 모집단 크기를 정확히 센다")
	void countsMultipleMembersAcrossAllThreeBranches() {
		Holding soldAfterT = createHolding();
		HoldingLot lotA = createBuyLot(soldAfterT, BigDecimal.valueOf(7), T.minusHours(3));
		allocate(createSellTrade(soldAfterT.getAccount(), BigDecimal.valueOf(7), T.plusMinutes(30)),
			lotA, BigDecimal.valueOf(7));

		Holding partialHolder = createHolding();
		HoldingLot lotB = createBuyLot(partialHolder, BigDecimal.valueOf(20), T.minusHours(2));
		allocate(createSellTrade(partialHolder.getAccount(), BigDecimal.valueOf(5), T.minusHours(1)),
			lotB, BigDecimal.valueOf(5));

		Holding soldBeforeT = createHolding();
		HoldingLot lotC = createBuyLot(soldBeforeT, BigDecimal.valueOf(10), T.minusHours(3));
		allocate(createSellTrade(soldBeforeT.getAccount(), BigDecimal.valueOf(10), T.minusHours(1)),
			lotC, BigDecimal.valueOf(10));

		Holding boughtAfterT = createHolding();
		createBuyLot(boughtAfterT, BigDecimal.valueOf(15), T.plusHours(1));

		int holderCount = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);

		assertThat(holderCount).isEqualTo(2);
	}

	@Test
	@DisplayName("remaining_quantity를 임의로 바꿔도 모집단 크기가 변하지 않는다 — original_quantity·allocated_quantity만 쓴다")
	void ignoresRemainingQuantityTampering() {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));

		int before = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);
		assertThat(before).isEqualTo(1);

		lot.consume(BigDecimal.valueOf(9));
		holdingLotRepository.saveAndFlush(lot);

		int after = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);

		assertThat(after).isEqualTo(before);
	}

	@Test
	@DisplayName("countHoldersAtTime의 반환형은 int라 회원 식별자·holding id를 구조적으로 담을 수 없다")
	void returnTypeCannotCarryIdentifiers() throws NoSuchMethodException {
		Method method = HolderPopulationQueryService.class.getMethod(
			"countHoldersAtTime", Long.class, LocalDateTime.class);

		assertThat(method.getReturnType()).isEqualTo(int.class);
	}

	@Test
	@DisplayName("populationSnapshotAtTime의 holderCount·minutesToSell이 개별 메서드 호출 결과와 일치한다")
	void populationSnapshotMatchesSeparateCalls() {
		Holding soldWithin30Min = createHolding();
		HoldingLot lotA = createBuyLot(soldWithin30Min, BigDecimal.valueOf(10), T.minusHours(1));
		allocate(createSellTrade(soldWithin30Min.getAccount(), BigDecimal.valueOf(10), T.plusMinutes(10)),
			lotA, BigDecimal.valueOf(10));

		Holding soldAfter30Min = createHolding();
		HoldingLot lotB = createBuyLot(soldAfter30Min, BigDecimal.valueOf(5), T.minusHours(1));
		allocate(createSellTrade(soldAfter30Min.getAccount(), BigDecimal.valueOf(5), T.plusHours(2)),
			lotB, BigDecimal.valueOf(5));

		Holding notSold = createHolding();
		createBuyLot(notSold, BigDecimal.valueOf(3), T.minusHours(1));

		int expectedHolderCount = holderPopulationQueryService.countHoldersAtTime(instrument.getId(), T);
		List<Integer> expectedMinutesToSell = holderPopulationQueryService
			.minutesToSellForHoldersAtTime(instrument.getId(), T);

		HolderPopulationQueryService.PopulationSnapshot snapshot = holderPopulationQueryService
			.populationSnapshotAtTime(instrument.getId(), T);

		assertThat(snapshot.holderCount()).isEqualTo(expectedHolderCount).isEqualTo(3);
		assertThat(snapshot.minutesToSell())
			.containsExactlyInAnyOrderElementsOf(expectedMinutesToSell)
			.containsExactlyInAnyOrder(10, 120);
	}

	private Holding createHolding() {
		memberSeq++;
		User user = userRepository.saveAndFlush(
			User.create("trader" + memberSeq + "@finplay.com", "hash", "trader" + memberSeq, T));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, T));
		return holdingRepository.saveAndFlush(Holding.create(account, instrument, T));
	}

	private HoldingLot createBuyLot(Holding holding, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			holding.getAccount().getUser(), holding.getAccount(), instrument, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-buy-" + System.nanoTime(), "a".repeat(64), executedAt));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, holding.getAccount(), instrument, session, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, executedAt, executedAt));
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, executedAt, executedAt));
	}

	private Trade createSellTrade(Account account, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET,
			quantity, "idem-sell-" + System.nanoTime(), "b".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, OrderSide.SELL,
			BigDecimal.valueOf(75000), quantity,
			75000L * quantity.longValueExact(), 100L, 49_900L, executedAt, executedAt));
	}

	private void allocate(Trade sellTrade, HoldingLot lot, BigDecimal quantity) {
		lot.consume(quantity);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, quantity, 700_000L, 100L, sellTrade.getExecutedAt()));
	}
}
