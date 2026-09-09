package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.dto.response.AccountSummaryResponse;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.dto.response.OrderListResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.domain.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
@Import(TestcontainersConfiguration.class)
class LimitOrderPendingListIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 12, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

	@Autowired
	private LimitOrderCancelService limitOrderCancelService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private HoldingService holdingService;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private Clock clock;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	@Test
	void pendingListReturnsOnlyOwnPendingOrdersNewestFirstAndExcludesOtherUsers() {
		User owner = createUser("pending-owner");
		createAccount(owner);
		User other = createUser("pending-other");
		createAccount(other);
		Instrument instrument = createCryptoInstrument("PENDOWN");

		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100000"), LocalDateTime.now(clock));
		orderService.createOrder(owner.getId(), "idem-pendown-seed",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("10")));

		LimitOrderResponse ownerBuy = createLimitOrder(owner, instrument, OrderSide.BUY,
			new BigDecimal("0.01"), new BigDecimal("10000000"), "idem-pendown-buy");
		LimitOrderResponse ownerSell = createLimitOrder(owner, instrument, OrderSide.SELL,
			new BigDecimal("2"), new BigDecimal("100000"), "idem-pendown-sell");
		LimitOrderResponse otherOrder = createLimitOrder(other, instrument, OrderSide.BUY,
			new BigDecimal("0.01"), new BigDecimal("10000000"), "idem-pendown-other");

		OrderListResponse response = orderService.getMyPendingOrders(owner.getId(),
			Market.CRYPTO, null, 100);

		assertThat(response.hasNext()).isFalse();
		assertThat(response.content()).allMatch(item -> item.status().equals("PENDING"));
		assertThat(response.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(ownerSell.orderId(), ownerBuy.orderId());
		assertThat(response.content().get(0).limitPrice()).isEqualByComparingTo(new BigDecimal("100000"));
		assertThat(response.content().get(1).limitPrice()).isEqualByComparingTo(new BigDecimal("10000000"));

		OrderListResponse otherResponse = orderService.getMyPendingOrders(other.getId(),
			Market.CRYPTO, null, 100);
		assertThat(otherResponse.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(otherOrder.orderId());
	}

	@Test
	void pendingListExcludesOrdersAfterFillOrCancel() {
		User user = createUser("pending-transition");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("PENDTR");

		LimitOrderResponse toFill = createLimitOrder(user, instrument, OrderSide.BUY,
			new BigDecimal("0.01"), new BigDecimal("10000000"), "idem-pendtr-fill");
		LimitOrderResponse toCancel = createLimitOrder(user, instrument, OrderSide.BUY,
			new BigDecimal("0.01"), new BigDecimal("9000000"), "idem-pendtr-cancel");
		LimitOrderResponse remaining = createLimitOrder(user, instrument, OrderSide.BUY,
			new BigDecimal("0.01"), new BigDecimal("8000000"), "idem-pendtr-remain");

		OrderListResponse beforeTransition = orderService.getMyPendingOrders(user.getId(),
			Market.CRYPTO, null, 100);
		assertThat(beforeTransition.content()).hasSize(3);

		limitOrderFillService.fillIfPending(toFill.orderId());
		limitOrderCancelService.cancelOrder(user.getId(), toCancel.orderId());

		OrderListResponse afterTransition = orderService.getMyPendingOrders(user.getId(),
			Market.CRYPTO, null, 100);
		assertThat(afterTransition.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(remaining.orderId());
	}

	@Test
	void pendingListCursorPaginationCoversAllOrdersWithoutDuplicatesOrGaps() {
		User user = createUser("pending-page");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("PENDPG");

		List<Long> createdIds = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			LimitOrderResponse created = createLimitOrder(user, instrument, OrderSide.BUY,
				new BigDecimal("0.001"), new BigDecimal(String.valueOf(5_000_000 + i)), "idem-pendpg-" + i);
			createdIds.add(created.orderId());
		}

		List<Long> pagedIds = collectAllPendingOrderIdsByCursor(user.getId(),
			Market.CRYPTO, 2);
		List<Long> singlePageIds = orderService
			.getMyPendingOrders(user.getId(), Market.CRYPTO, null, 100)
			.content().stream().map(OrderListItemResponse::orderId).toList();

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(singlePageIds);
		assertThat(pagedIds).containsExactlyInAnyOrderElementsOf(createdIds);
	}

	@Test
	void accountSummaryReservedCashReflectsReservationAndReturnsToZeroAfterCancel() {
		User user = createUser("reserved-cash");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("RESVCASH");

		AccountSummaryResponse before = accountService.getAccountSummary(user.getId(),
			Market.CRYPTO);
		assertThat(before.reservedCash()).isZero();
		long cashBalanceBefore = before.cashBalance();

		LimitOrderResponse created = createLimitOrder(user, instrument, OrderSide.BUY,
			new BigDecimal("0.1"), new BigDecimal("10000000"), "idem-resvcash-buy");

		Account reservedAccount = accountRepository.findById(account.getId()).orElseThrow();
		long expectedReservedCash = reservedAccount.getReservedCash();
		assertThat(expectedReservedCash).isGreaterThan(0L);

		AccountSummaryResponse afterReserve = accountService.getAccountSummary(user.getId(),
			Market.CRYPTO);
		assertThat(afterReserve.reservedCash()).isEqualTo(expectedReservedCash);
		assertThat(afterReserve.cashBalance()).isEqualTo(cashBalanceBefore);

		limitOrderCancelService.cancelOrder(user.getId(), created.orderId());

		AccountSummaryResponse afterCancel = accountService.getAccountSummary(user.getId(),
			Market.CRYPTO);
		assertThat(afterCancel.reservedCash()).isZero();
		assertThat(afterCancel.cashBalance()).isEqualTo(cashBalanceBefore);
	}

	@Test
	void holdingsReservedQuantityReflectsReservationAndReturnsToZeroAfterFill() {
		User user = createUser("reserved-qty");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("RESVQTY");

		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100000"), LocalDateTime.now(clock));
		BigDecimal initialQuantity = new BigDecimal("10");
		orderService.createOrder(user.getId(), "idem-resvqty-seed",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", initialQuantity));

		List<HoldingListItemResponse> beforeReserve = holdingService.getHoldings(user.getId(),
			Market.CRYPTO);
		assertThat(beforeReserve).singleElement()
			.satisfies(h -> assertThat(h.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));

		BigDecimal sellQuantity = new BigDecimal("4");
		LimitOrderResponse created = createLimitOrder(user, instrument, OrderSide.SELL,
			sellQuantity, new BigDecimal("100000"), "idem-resvqty-sell");

		List<HoldingListItemResponse> afterReserve = holdingService.getHoldings(user.getId(),
			Market.CRYPTO);
		assertThat(afterReserve).singleElement().satisfies(h -> {
			assertThat(h.reservedQuantity()).isEqualByComparingTo(sellQuantity);
			assertThat(h.quantity()).isEqualByComparingTo(initialQuantity);
		});

		limitOrderFillService.fillIfPending(created.orderId());

		List<HoldingListItemResponse> afterFill = holdingService.getHoldings(user.getId(),
			Market.CRYPTO);
		assertThat(afterFill).singleElement().satisfies(h -> {
			assertThat(h.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(h.quantity()).isEqualByComparingTo(initialQuantity.subtract(sellQuantity));
		});
	}

	private List<Long> collectAllPendingOrderIdsByCursor(
		Long userId, com.finplay.api.domain.market.entity.Market market, int limit) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20);

			OrderListResponse page = orderService.getMyPendingOrders(userId, market, cursor, limit);
			page.content().forEach(item -> {
				assertThat(item.status()).isEqualTo("PENDING");
				ids.add(item.orderId());
			});
			hasNext = page.hasNext();
			if (hasNext) {
				cursor = page.nextCursor();
			} else {
				assertThat(page.nextCursor()).isNull();
			}
		}
		return ids;
	}

	private LimitOrderResponse createLimitOrder(
		User user, Instrument instrument, OrderSide side, BigDecimal quantity, BigDecimal limitPrice,
		String idempotencyKey) {
		return limitOrderService.createLimitOrder(user.getId(), idempotencyKey,
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), side, quantity, limitPrice));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
