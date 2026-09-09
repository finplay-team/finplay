package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
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
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.dto.response.OrderListResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class OrderListIntegrationTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private OrderService orderService;

	@Autowired
	private LimitOrderService limitOrderService;

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

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Test
	void getMyOrdersReturnsOwnOrdersNewestFirstWithFieldContractAndExcludesOtherUsers() {
		User owner = createUser("list-owner");
		createAccount(owner, Market.STOCK);
		User other = createUser("list-other");
		createAccount(other, Market.STOCK);
		Instrument instrument = createStockInstrument("LIST");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		orderService.createOrder(
			owner.getId(), "list-owner-idem-1", buyRequest(Market.STOCK, instrument.getId(), "10"));
		orderService.createOrder(
			other.getId(), "list-other-idem-1", buyRequest(Market.STOCK, instrument.getId(), "5"));

		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(
			owner.getId(), "list-owner-idem-2", buyRequest(Market.STOCK, instrument.getId(), "20"));

		OrderListResponse response = orderService.getMyOrders(
			owner.getId(), Market.STOCK, null, 100);
		List<OrderListItemResponse> result = response.content();

		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		assertThat(result).hasSize(2);
		assertThat(result).extracting(OrderListItemResponse::requestedAt)
			.containsExactly(BASE_NOW.plusMinutes(1), BASE_NOW);
		assertThat(result).extracting(OrderListItemResponse::quantity)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("20"), new BigDecimal("10"));

		OrderListItemResponse latest = result.get(0);
		assertThat(latest.market()).isEqualTo("STOCK");
		assertThat(latest.instrumentId()).isEqualTo(instrument.getId());
		assertThat(latest.side()).isEqualTo("BUY");
		assertThat(latest.orderType()).isEqualTo("MARKET");
		assertThat(latest.status()).isEqualTo("FILLED");

		List<OrderListItemResponse> otherResult = orderService.getMyOrders(
			other.getId(), Market.STOCK, null, 100).content();
		assertThat(otherResult).hasSize(1);
		assertThat(otherResult.get(0).quantity()).isEqualByComparingTo("5");
		assertThat(result).noneMatch(item -> item.orderId().equals(otherResult.get(0).orderId()));

	}

	@Test
	void getMyOrdersReturnsEmptyListForUserWithNoOrders() {
		User newUser = createUser("list-no-orders");
		createAccount(newUser, Market.STOCK);

		OrderListResponse response = orderService.getMyOrders(
			newUser.getId(), Market.STOCK, null, 100);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyOrdersFiltersByMarketAndExcludesOtherMarketOrders() {
		User user = createUser("list-market");
		createAccount(user, Market.STOCK);
		createAccount(user, Market.CRYPTO);

		Instrument stockInstrument = createStockInstrument("MKST");
		createCandle(stockInstrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(
			user.getId(), "list-market-stock-1", buyRequest(Market.STOCK, stockInstrument.getId(), "1"));

		Instrument cryptoInstrument = createCryptoInstrument("MKCO");
		seedCryptoPrice(cryptoInstrument, new BigDecimal("50000000"));
		orderService.createOrder(
			user.getId(), "list-market-crypto-1", buyRequest(Market.CRYPTO, cryptoInstrument.getId(), "0.01"));

		OrderListResponse stockResult = orderService.getMyOrders(
			user.getId(), Market.STOCK, null, 100);
		OrderListResponse cryptoResult = orderService.getMyOrders(
			user.getId(), Market.CRYPTO, null, 100);

		assertThat(stockResult.content()).hasSize(1);
		assertThat(stockResult.content().get(0).market()).isEqualTo("STOCK");
		assertThat(stockResult.content().get(0).instrumentId()).isEqualTo(stockInstrument.getId());

		assertThat(cryptoResult.content()).hasSize(1);
		assertThat(cryptoResult.content().get(0).market()).isEqualTo("CRYPTO");
		assertThat(cryptoResult.content().get(0).instrumentId()).isEqualTo(cryptoInstrument.getId());
	}

	@Test
	void createOrderFillsCryptoOrderEvenWhenLastObservationIsHoursOld() {
		User user = createUser("list-old-obs");
		createAccount(user, Market.CRYPTO);
		Instrument cryptoInstrument = createCryptoInstrument("OLDOBS");

		clock.set(BASE_NOW.minusHours(3));
		priceStore.saveTick(cryptoInstrument.getSymbol(), new BigDecimal("50000000"), LocalDateTime.now(clock));
		clock.set(BASE_NOW);

		orderService.createOrder(
			user.getId(), "list-old-obs-1", buyRequest(Market.CRYPTO, cryptoInstrument.getId(), "0.01"));

		OrderListResponse result = orderService.getMyOrders(
			user.getId(), Market.CRYPTO, null, 100);

		assertThat(result.content()).hasSize(1);
		assertThat(result.content().get(0).status()).isEqualTo("FILLED");
	}

	@Test
	void getMyOrdersExposesLimitPriceForLimitOrdersAndNullForMarketOrders() {
		User user = createUser("list-limitprice");
		createAccount(user, Market.CRYPTO);
		Instrument cryptoInstrument = createCryptoInstrument("LPRICE");
		seedCryptoPrice(cryptoInstrument, new BigDecimal("50000000"));

		orderService.createOrder(user.getId(), "list-limitprice-market",
			buyRequest(Market.CRYPTO, cryptoInstrument.getId(), "0.01"));

		BigDecimal limitPrice = new BigDecimal("10000000");
		limitOrderService.createLimitOrder(user.getId(), "list-limitprice-limit",
			new LimitOrderCreateRequest(
				Market.CRYPTO, cryptoInstrument.getId(), OrderSide.BUY, new BigDecimal("0.01"), limitPrice));

		OrderListResponse response = orderService.getMyOrders(
			user.getId(), Market.CRYPTO, null, 100);
		List<OrderListItemResponse> result = response.content();

		assertThat(result).hasSize(2);
		OrderListItemResponse marketItem = result.stream()
			.filter(item -> item.orderType().equals("MARKET"))
			.findFirst()
			.orElseThrow();
		OrderListItemResponse limitItem = result.stream()
			.filter(item -> item.orderType().equals("LIMIT"))
			.findFirst()
			.orElseThrow();

		assertThat(marketItem.limitPrice()).isNull();
		assertThat(limitItem.status()).isEqualTo("PENDING");
		assertThat(limitItem.limitPrice()).isEqualByComparingTo(limitPrice);
	}

	@Test
	void cursorPaginationAcrossPagesMatchesSinglePageFetchInSetAndOrderAndLastPageHasNoNext() {
		User user = createUser("list-page");
		createAccount(user, Market.STOCK);
		Instrument instrument = createStockInstrument("PAGE");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));

		for (int i = 1; i <= 5; i++) {
			clock.set(BASE_NOW.plusMinutes(i));
			orderService.createOrder(user.getId(), "list-page-buy-" + i,
				buyRequest(Market.STOCK, instrument.getId(), String.valueOf(i)));
		}

		List<Long> pagedIds = collectAllOrderIdsByCursor(
			user.getId(), Market.STOCK, 2);
		List<Long> singleCallIds = collectSinglePageOrderIds(
			user.getId(), Market.STOCK, 100);

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(singleCallIds);
	}

	@Test
	void getMyOrdersRejectsWhenAccountForRequestedMarketDoesNotExist() {
		User user = createUser("list-no-crypto-acct");
		createAccount(user, Market.STOCK);

		assertThatThrownBy(() -> orderService.getMyOrders(
			user.getId(), Market.CRYPTO, null, 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(
				exception -> assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	private List<Long> collectAllOrderIdsByCursor(Long userId, com.finplay.api.domain.market.entity.Market market,
		int limit) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20);

			OrderListResponse page = orderService.getMyOrders(userId, market, cursor, limit);
			page.content().forEach(item -> ids.add(item.orderId()));
			hasNext = page.hasNext();
			if (hasNext) {
				cursor = page.nextCursor();
			} else {
				assertThat(page.nextCursor()).isNull();
			}
		}
		return ids;
	}

	private List<Long> collectSinglePageOrderIds(Long userId, com.finplay.api.domain.market.entity.Market market,
		int limit) {
		OrderListResponse page = orderService.getMyOrders(userId, market, null, limit);
		return page.content().stream().map(OrderListItemResponse::orderId).toList();
	}

	private OrderCreateRequest buyRequest(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user, com.finplay.api.domain.market.entity.Market market) {
		return accountRepository.saveAndFlush(Account.create(user, market, BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

}
