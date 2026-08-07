// 시장가 매수 성공(주문·체결·현금차감·holding·lot 원자 저장)·현금부족(무흔적)·재매수(평균단가 재계산) 핵심 시나리오를 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
class OrderBuyIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

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
	void stockBuySucceedsAndPersistsOrderTradeCashHoldingAndLotInOneTransaction() {
		User user = createUser("buy-success");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("BUYOK");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		OrderResponse response = orderService.createOrder(
			user.getId(), "idem-buy-success", buyRequest(instrument.getId(), "10"));

		// price=70000, quantity=10 → amount=700000, fee=700000*0.00015=105(내림 전 정확히 105)
		assertThat(response.amount()).isEqualTo(700_000L);
		assertThat(response.fee()).isEqualTo(105L);

		Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(reloadedAccount.getCashBalance()).isEqualTo(10_000_000L - 700_105L);

		assertThat(orderRepository.findById(response.orderId())).isPresent();
		assertThat(tradeRepository.findById(response.tradeId())).isPresent();
		assertThat(stockReplaySessionIdOf(response.tradeId()))
			.isEqualTo(stockReplaySessionRepository.findByServiceDate(TRADING_DATE).orElseThrow().getId());

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holding.getQuantity()).isEqualByComparingTo("10");
		assertThat(holding.getAveragePrice()).isEqualByComparingTo("70000");
		assertThat(holding.isActive()).isTrue();

		List<HoldingLot> lots = holdingLotsFor(holding);
		assertThat(lots).hasSize(1);
		assertThat(lots.get(0).getOriginalQuantity()).isEqualByComparingTo("10");
		assertThat(lots.get(0).getRemainingQuantity()).isEqualByComparingTo("10");
		assertThat(lots.get(0).getUnitCost()).isEqualByComparingTo("70000");
	}

	@Test
	void cryptoBuyPersistsTradeWithoutReplaySession() {
		User user = createUser("cb-null");
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		String symbol = "CB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "코인", new BigDecimal("0.00000001"), 5000L, true, BASE_NOW));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, new BigDecimal("100000"), BASE_NOW);
		cryptoPriceKeyToCleanUp = "price:crypto:" + symbol;

		OrderResponse response = orderService.createOrder(user.getId(), "crypto-buy-null-session",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("0.1")));

		assertThat(stockReplaySessionIdOf(response.tradeId())).isNull();
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance()).isLessThan(10_000_000L);
	}

	@Test
	void stockBuyWithoutCurrentSessionFailsAndRollsBackOrderTradeAccountAndHolding() {
		User user = createUser("no-session");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("NOSESS");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		clock.set(LocalDateTime.of(2099, 1, 5, 10, 0));
		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		long holdingsBefore = holdingRepository.count();

		assertThatThrownBy(() -> orderService.createOrder(
			user.getId(), "stock-buy-no-session", buyRequest(instrument.getId(), "1")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(holdingRepository.count()).isEqualTo(holdingsBefore);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(10_000_000L);
		assertThat(holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())).isEmpty();
	}

	@Test
	void stockBuyFailsWithInsufficientCashAndLeavesNoTraceInAnyOfFourTables() {
		User user = createUser("buy-insufficient");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("BUYNG");
		// 계좌 기본 현금 10,000,000보다 큰 체결금액이 되도록 고가 분봉을 준비한다.
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("50000000"));
		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		long holdingsBefore = holdingRepository.count();
		long holdingLotsBefore = holdingLotRepository.count();

		assertThatThrownBy(() -> orderService.createOrder(
			user.getId(), "idem-buy-insufficient", buyRequest(instrument.getId(), "1")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_CASH));

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(holdingRepository.count()).isEqualTo(holdingsBefore);
		assertThat(holdingLotRepository.count()).isEqualTo(holdingLotsBefore);
		assertThat(holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())).isEmpty();
		Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(reloadedAccount.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void rebuyingSameAccountAndInstrumentRecalculatesWeightedAveragePriceAndCreatesTwoLots() {
		User user = createUser("buy-rebuy");
		Account account = createAccount(user);
		Instrument instrument = createStockInstrument("REBUY");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		// 첫 매수: 10:00 시각 → 09:59에 마감된 분봉(60000)이 체결가
		orderService.createOrder(user.getId(), "idem-rebuy-1", buyRequest(instrument.getId(), "10"));
		// 두 번째 매수: 10:01로 시각을 이동 → 10:00에 마감된 분봉(80000)이 체결가
		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "idem-rebuy-2", buyRequest(instrument.getId(), "10"));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		// 평균단가 = (10*60000 + 10*80000) / 20 = 70000
		assertThat(holding.getQuantity()).isEqualByComparingTo("20");
		assertThat(holding.getAveragePrice()).isEqualByComparingTo("70000");

		List<HoldingLot> lots = holdingLotsFor(holding);
		assertThat(lots).hasSize(2);
		assertThat(lots)
			.extracting(lot -> lot.getUnitCost().stripTrailingZeros())
			.containsExactlyInAnyOrder(
				new BigDecimal("60000").stripTrailingZeros(), new BigDecimal("80000").stripTrailingZeros());
	}

	private List<HoldingLot> holdingLotsFor(Holding holding) {
		return holdingLotRepository
			.findAll()
			.stream()
			.filter(lot -> lot.getHolding().getId().equals(holding.getId()))
			.toList();
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, BASE_NOW));
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
