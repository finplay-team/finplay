// 시장가 매도의 FIFO lot 배분·실현손익 계산·보유수량 초과 거부(무흔적)·전량매도 비활성화 핵심 시나리오를 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class OrderSellIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);
	private static final LocalTime THIRD_CANDLE_TIME = LocalTime.of(10, 1);

	@Autowired
	private OrderService orderService;

	@Autowired
	private TestClock clock;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

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
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String cryptoPriceKeyToCleanUp;

	private Long stockReplaySessionIdOf(Long tradeId) {
		return jdbcTemplate.queryForObject(
			"SELECT stock_replay_session_id FROM trades WHERE id = ?", Long.class, tradeId);
	}

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		if (cryptoPriceKeyToCleanUp != null) {
			redisTemplate.delete(cryptoPriceKeyToCleanUp);
		}
	}

	@Test
	void partialSellConsumesOnlyEarliestLotInFifoOrderAndPersistsSingleAllocation() {
		User user = createUser("sell-fifo-partial");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("SFIFO");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		createCandle(instrument, THIRD_CANDLE_TIME, new BigDecimal("100000"));

		// 매수1: 10:00 시각 → 09:59 분봉(60000) 체결가, 매수2: 10:01 시각 → 10:00 분봉(80000) 체결가
		orderService.createOrder(user.getId(), "idem-fifo-buy-1", buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "idem-fifo-buy-2", buyRequest(instrument.getId(), "10"));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		List<HoldingLot> lotsBeforeSell = holdingLotsFor(holding);
		HoldingLot earliestLot = lotsBeforeSell.get(0);
		HoldingLot laterLot = lotsBeforeSell.get(1);
		assertThat(earliestLot.getUnitCost()).isEqualByComparingTo("60000");
		assertThat(laterLot.getUnitCost()).isEqualByComparingTo("80000");

		// 매도: 10:02 시각 → 10:01 분봉(100000) 체결가, 5주만 매도(먼저 산 lot의 일부만 소진)
		clock.set(BASE_NOW.plusMinutes(2));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-fifo-sell", sellRequest(instrument.getId(), "5"));

		// amount=100000*5=500000, fee=floor(500000*0.00015)=75
		assertThat(response.amount()).isEqualTo(500_000L);
		assertThat(response.fee()).isEqualTo(75L);
		// 배분원가=60000*5=300000, 배분매수수수료=floor(90*5/10)=45 (매수1 fee=floor(600000*0.00015)=90)
		// realizedPnl = (500000-75) - (300000+45) = 199880
		assertThat(response.realizedPnl()).isEqualTo(199_880L);
		assertThat(stockReplaySessionIdOf(response.tradeId()))
			.isEqualTo(stockReplaySessionRepository.findByServiceDate(TRADING_DATE).orElseThrow().getId());

		HoldingLot reloadedEarliestLot = holdingLotRepository.findById(earliestLot.getId()).orElseThrow();
		HoldingLot reloadedLaterLot = holdingLotRepository.findById(laterLot.getId()).orElseThrow();
		// FIFO 순서 검증: 먼저 산 lot만 차감되고, 나중 lot은 손대지 않는다.
		assertThat(reloadedEarliestLot.getRemainingQuantity()).isEqualByComparingTo("5");
		assertThat(reloadedLaterLot.getRemainingQuantity()).isEqualByComparingTo("10");

		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAll()
			.stream()
			.filter(a -> a.getSellTrade().getId().equals(response.tradeId()))
			.toList();
		assertThat(allocations).hasSize(1);
		TradeAllocation allocation = allocations.get(0);
		assertThat(allocation.getHoldingLot().getId()).isEqualTo(earliestLot.getId());
		assertThat(allocation.getAllocatedQuantity()).isEqualByComparingTo("5");
		assertThat(allocation.getAllocatedCost()).isEqualTo(300_000L);
		assertThat(allocation.getAllocatedBuyFee()).isEqualTo(45L);

		// 비즈니스 규칙: 매수 lot의 (최초수량 - 배분수량 합계) = 잔여수량이 항상 성립해야 한다.
		assertLotQuantityInvariant(earliestLot.getId());
		assertLotQuantityInvariant(laterLot.getId());

		Holding reloadedHolding = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(reloadedHolding.getQuantity()).isEqualByComparingTo("15");
		assertThat(reloadedHolding.isActive()).isTrue();
	}

	@Test
	void cryptoBuyAndSellPersistTradesWithoutReplaySession() {
		User user = createUser("cs-null");
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		String symbol = "CS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "코인", new BigDecimal("0.00000001"), 5000L, true, BASE_NOW));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, new BigDecimal("100000"), BASE_NOW);
		cryptoPriceKeyToCleanUp = "price:crypto:" + symbol;

		OrderResponse buy = orderService.createOrder(user.getId(), "crypto-buy-before-sell",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("0.1")));
		OrderResponse sell = orderService.createOrder(user.getId(), "crypto-sell-null-session",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET", new BigDecimal("0.1")));

		assertThat(stockReplaySessionIdOf(buy.tradeId())).isNull();
		assertThat(stockReplaySessionIdOf(sell.tradeId())).isNull();
		assertThat(holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow().getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void stockSellWithoutCurrentSessionFailsAndRollsBackOrderTradeAccountAndHolding() {
		User user = createUser("s-no-session");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("SNOS");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(user.getId(), "buy-before-no-session-sell", buyRequest(instrument.getId(), "2"));
		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId()).orElseThrow();
		long cashBefore = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();
		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		clock.set(LocalDateTime.of(2099, 1, 5, 10, 0));

		assertThatThrownBy(() -> orderService.createOrder(
			user.getId(), "stock-sell-no-session", sellRequest(instrument.getId(), "1")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(cashBefore);
		assertThat(holdingRepository.findById(holdingBefore.getId()).orElseThrow().getQuantity())
			.isEqualByComparingTo("2");
	}

	@Test
	void sellConsumingMultipleLotsAllocatesCostAcrossLotsAndComputesRealizedPnl() {
		User user = createUser("sell-multi-lot");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("SMULTI");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		createCandle(instrument, THIRD_CANDLE_TIME, new BigDecimal("100000"));

		orderService.createOrder(user.getId(), "idem-multi-buy-1", buyRequest(instrument.getId(), "10"));
		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "idem-multi-buy-2", buyRequest(instrument.getId(), "10"));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		List<HoldingLot> lots = holdingLotsFor(holding);
		HoldingLot lot1 = lots.get(0);
		HoldingLot lot2 = lots.get(1);
		long cashBeforeSell = accountRepository.findById(account.getId()).orElseThrow().getCashBalance();

		// 매도 15주 → lot1(10주) 전량 소진 + lot2(10주 중 5주) 부분 소진, 하나의 매도가 두 lot을 소비한다.
		clock.set(BASE_NOW.plusMinutes(2));
		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-multi-sell", sellRequest(instrument.getId(), "15"));

		// amount=100000*15=1500000, fee=floor(1500000*0.00015)=225
		assertThat(response.amount()).isEqualTo(1_500_000L);
		assertThat(response.fee()).isEqualTo(225L);
		// lot1 전량소진 배분: cost=600000(=buyTrade.amount), buyFee=90(=lot.buyFee, 최초 배분이므로 누적 0 차감)
		// lot2 부분소진(5/10) 배분: cost=floor(80000*5)=400000, buyFee=floor(120*5/10)=60
		// totalAllocatedCost=1000000, totalAllocatedBuyFee=150
		// realizedPnl = (1500000-225) - (1000000+150) = 499625
		assertThat(response.realizedPnl()).isEqualTo(499_625L);

		HoldingLot reloadedLot1 = holdingLotRepository.findById(lot1.getId()).orElseThrow();
		HoldingLot reloadedLot2 = holdingLotRepository.findById(lot2.getId()).orElseThrow();
		assertThat(reloadedLot1.getRemainingQuantity()).isEqualByComparingTo("0");
		assertThat(reloadedLot2.getRemainingQuantity()).isEqualByComparingTo("5");

		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAll()
			.stream()
			.filter(a -> a.getSellTrade().getId().equals(response.tradeId()))
			.sorted(Comparator.comparing(a -> a.getHoldingLot().getId()))
			.toList();
		assertThat(allocations).hasSize(2);
		TradeAllocation lot1Allocation = allocations.get(0);
		assertThat(lot1Allocation.getHoldingLot().getId()).isEqualTo(lot1.getId());
		assertThat(lot1Allocation.getAllocatedQuantity()).isEqualByComparingTo("10");
		assertThat(lot1Allocation.getAllocatedCost()).isEqualTo(600_000L);
		assertThat(lot1Allocation.getAllocatedBuyFee()).isEqualTo(90L);

		TradeAllocation lot2Allocation = allocations.get(1);
		assertThat(lot2Allocation.getHoldingLot().getId()).isEqualTo(lot2.getId());
		assertThat(lot2Allocation.getAllocatedQuantity()).isEqualByComparingTo("5");
		assertThat(lot2Allocation.getAllocatedCost()).isEqualTo(400_000L);
		assertThat(lot2Allocation.getAllocatedBuyFee()).isEqualTo(60L);

		// 비즈니스 규칙: 매수 lot의 (최초수량 - 배분수량 합계) = 잔여수량이 항상 성립해야 한다(두 lot 모두).
		assertLotQuantityInvariant(lot1.getId());
		assertLotQuantityInvariant(lot2.getId());

		Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(reloadedAccount.getCashBalance()).isEqualTo(cashBeforeSell + 1_500_000L - 225L);
		assertThat(reloadedAccount.getRealizedPnl()).isEqualTo(499_625L);

		Holding reloadedHolding = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(reloadedHolding.getQuantity()).isEqualByComparingTo("5");
		assertThat(reloadedHolding.isActive()).isTrue();
	}

	@Test
	void sellFailsWithInsufficientQtyAndLeavesNoTraceInOrderTradeHoldingLotAllocationOrAccountTables() {
		User user = createUser("sell-insufficient");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("SOVER");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		orderService.createOrder(user.getId(), "idem-over-buy", buyRequest(instrument.getId(), "10"));

		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		long holdingLotsBefore = holdingLotRepository.count();
		long allocationsBefore = tradeAllocationRepository.count();
		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal quantityBefore = holdingBefore.getQuantity();
		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long cashBefore = accountBefore.getCashBalance();
		long realizedPnlBefore = accountBefore.getRealizedPnl();

		// 보유수량(10)보다 많은 11주 매도 시도 → 409 INSUFFICIENT_QTY, 어떤 테이블에도 흔적을 남기지 않는다.
		clock.set(BASE_NOW.plusMinutes(1));
		assertThatThrownBy(() -> orderService.createOrder(
			user.getId(), "idem-over-sell", sellRequest(instrument.getId(), "11")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(holdingLotRepository.count()).isEqualTo(holdingLotsBefore);
		assertThat(tradeAllocationRepository.count()).isEqualTo(allocationsBefore);

		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		assertThat(holdingAfter.isActive()).isTrue();

		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(accountAfter.getCashBalance()).isEqualTo(cashBefore);
		assertThat(accountAfter.getRealizedPnl()).isEqualTo(realizedPnlBefore);
	}

	@Test
	void fullSellZeroesHoldingQuantityAndDeactivatesIt() {
		User user = createUser("sell-full");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("SFULL");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		orderService.createOrder(user.getId(), "idem-full-buy", buyRequest(instrument.getId(), "10"));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		HoldingLot lot = holdingLotsFor(holding).get(0);

		// 전량(10주) 매도 → holding 잔량 0, isActive=false, lot 잔여수량도 0
		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "idem-full-sell", sellRequest(instrument.getId(), "10"));

		Holding reloadedHolding = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(reloadedHolding.getQuantity()).isEqualByComparingTo("0");
		assertThat(reloadedHolding.isActive()).isFalse();

		HoldingLot reloadedLot = holdingLotRepository.findById(lot.getId()).orElseThrow();
		assertThat(reloadedLot.getRemainingQuantity()).isEqualByComparingTo("0");

		// 비즈니스 규칙: 매수 lot의 (최초수량 - 배분수량 합계) = 잔여수량이 항상 성립해야 한다(전량 소진 케이스).
		assertLotQuantityInvariant(lot.getId());
	}

	// 매수 lot의 (최초수량 - 해당 lot에 대한 배분수량 합계) = 잔여수량 관계를 실제 DB 상태로 검증한다
	// (spec.md 비즈니스 규칙 "lot의 잔여수량은 배분 합계와 항상 일치해야 한다").
	private void assertLotQuantityInvariant(Long holdingLotId) {
		HoldingLot lot = holdingLotRepository.findById(holdingLotId).orElseThrow();
		BigDecimal allocatedSum = tradeAllocationRepository
			.findAll()
			.stream()
			.filter(a -> a.getHoldingLot().getId().equals(holdingLotId))
			.map(TradeAllocation::getAllocatedQuantity)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		assertThat(lot.getOriginalQuantity().subtract(allocatedSum))
			.isEqualByComparingTo(lot.getRemainingQuantity());
	}

	private List<HoldingLot> holdingLotsFor(Holding holding) {
		return holdingLotRepository
			.findAll()
			.stream()
			.filter(lot -> lot.getHolding().getId().equals(holding.getId()))
			.sorted(Comparator.comparing(HoldingLot::getExecutedAt).thenComparing(HoldingLot::getId))
			.toList();
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

}
