package com.finplay.api.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.dto.response.AccountSummaryResponse;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class AccountSummaryIntegrationTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private AccountService accountService;

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
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private String cryptoPriceKeyToCleanUp;

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
			cryptoPriceKeyToCleanUp = null;
		}
	}

	@Test
	void emptyAccountImmediatelyAfterSignupReturnsAllZeroExceptSeedMoneyCashBalance() {
		User user = createUser("summary-signup");
		accountService.createAccountsFor(user);

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), Market.STOCK);

		assertThat(response.cashBalance()).isEqualTo(10_000_000L);
		assertThat(response.holdingsValue()).isZero();
		assertThat(response.totalValue()).isEqualTo(10_000_000L);
		assertThat(response.realizedPnl()).isZero();
		assertThat(response.unrealizedPnl()).isZero();
	}

	@Test
	void stockBuyThenSummaryMatchesLedgerAndLatestQuoteExactly() {
		User user = createUser("summary-buy");
		createAccount(user);
		Instrument instrument = createStockInstrument("SUMM");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		orderService.createOrder(user.getId(), "summary-buy-idem", buyRequest(instrument.getId(), "10"));

		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		clock.set(BASE_NOW.plusMinutes(1));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), Market.STOCK);

		long expectedCashBalance = 10_000_000L - 700_105L;
		long expectedHoldingsValue = 800_000L;
		long expectedUnrealizedPnl = 100_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(response.realizedPnl()).isZero();
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
	}

	@Test
	void includesUnavailablePricedHoldingAtCostBasisWithoutPnl() {
		User user = createUser("summary-badpx");
		createAccount(user, Market.CRYPTO);
		String symbol = "SUMBTC" + UUID.randomUUID().toString().substring(0, 6);
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "요약코인", BigDecimal.ONE, 0L, true, BASE_NOW));

		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, new BigDecimal("1000000"), BASE_NOW);
		cryptoPriceKeyToCleanUp = "price:crypto:" + symbol;

		orderService.createOrder(
			user.getId(), "summary-badpx-idem",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("1")));

		long expectedCashBalance = 10_000_000L - 1_000_500L;

		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), Market.CRYPTO);

		long expectedHoldingsValue = 1_000_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isZero();
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
	}

	@Test
	void otherUsersAccountPurchaseDoesNotLeakIntoOwnSummary() {
		User owner = createUser("summary-owner");
		createAccount(owner);
		User other = createUser("summary-other");
		createAccount(other);
		Instrument instrument = createStockInstrument("OTHR");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		orderService.createOrder(other.getId(), "summary-other-idem", buyRequest(instrument.getId(), "10"));

		AccountSummaryResponse ownerSummary = accountService.getAccountSummary(
			owner.getId(), Market.STOCK);

		assertThat(ownerSummary.cashBalance()).isEqualTo(10_000_000L);
		assertThat(ownerSummary.holdingsValue()).isZero();
		assertThat(ownerSummary.totalValue()).isEqualTo(10_000_000L);
		assertThat(ownerSummary.unrealizedPnl()).isZero();
	}

	@Test
	void stockPartialSellAfterBuyReflectsRealizedPnlInSummary() {
		User user = createUser("summary-sell");
		createAccount(user);
		Instrument instrument = createStockInstrument("SSELL");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		orderService.createOrder(user.getId(), "summary-sell-buy-idem", buyRequest(instrument.getId(), "10"));

		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "summary-sell-sell-idem", sellRequest(instrument.getId(), "4"));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), Market.STOCK);

		long expectedCashBalance = 9_799_850L;
		long expectedRealizedPnl = 159_904L;
		long expectedHoldingsValue = 600_000L;
		long expectedUnrealizedPnl = 240_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.realizedPnl()).isEqualTo(expectedRealizedPnl);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
	}

	@Test
	void totalValueIsComputedAsCashBalancePlusHoldingsValueAfterTutorialCompletionReward() {
		User user = createUser("summary-reward-gain");
		Account account = createAccount(user);

		account.addCash(5_000_000L);
		accountRepository.saveAndFlush(account);

		Instrument instrument = createStockInstrument("RWGN");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("500000"));

		orderService.createOrder(user.getId(), "summary-reward-gain-idem", buyRequest(instrument.getId(), "10"));

		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("600000"));
		clock.set(BASE_NOW.plusMinutes(1));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), Market.STOCK);

		long expectedCashBalance = 9_999_250L;
		long expectedHoldingsValue = 6_000_000L;
		long expectedUnrealizedPnl = 1_000_000L;
		long expectedTotalValue = 15_999_250L;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
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
		return createAccount(user, Market.STOCK);
	}

	private Account createAccount(User user, com.finplay.api.domain.market.entity.Market market) {
		return accountRepository.saveAndFlush(Account.create(user, market, BASE_NOW));
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
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
	}

}
