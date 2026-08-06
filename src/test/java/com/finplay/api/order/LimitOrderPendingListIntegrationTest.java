// LMT-004(이슈 #235) 미체결 목록 조회·계좌/보유 예약분 노출을 실제 Spring 컨텍스트(Testcontainers MySQL)로
// 엔드투엔드 검증하는 통합 테스트다. plan.md "테스트 계획" 통합 시나리오, spec.md 시나리오 16~20을 그대로 구현한다.
// LimitOrderConcurrencyIntegrationTest·OrderListIntegrationTest와 동일하게 서비스 메서드를 직접 호출한다
// (OrderService.getMyPendingOrders·AccountService.getAccountSummary·HoldingService.getHoldings가 각 컨트롤러의
// 유일한 위임 대상이므로 슬라이스 테스트가 이미 검증한 HTTP 매핑을 다시 검증하지 않는다).
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderListResponse;
import com.finplay.api.order.service.LimitOrderCancelService;
import com.finplay.api.order.service.LimitOrderFillService;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.service.HoldingService;
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

// OrderListIntegrationTest(2026-07-30 agent-mistakes.md 항목)와 동일하게 instruments에 saveAndFlush로 실제 커밋을
// 남기므로 @Transactional로 각 테스트 종료 시 롤백시킨다 — 그러지 않으면 종목 5건이 JVM 전역 싱글턴인
// Testcontainers MySQL에 실행 내내 남아 InstrumentRepositoryTest의 "정확히 28건" 단정이 실행 순서에 의존하게 된다.
// 롤백되지 않은 PENDING 주문이 다른 테스트가 밀어넣은 시세에 체결돼 공유 랭킹까지 오염시키는 것도 함께 막는다
// (PR #237 리뷰. 시드 코인 재사용안은 심볼이 겹쳐 이 오염을 오히려 실제로 일으켜 채택하지 않았다).
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

	// 시나리오 16·19: 여러 사용자·여러 상태의 지정가 주문이 섞여 있어도 조회자 본인의 PENDING 주문만,
	// 최신 요청순(id 내림차순)으로 반환하고 타 사용자 주문은 섞이지 않는다.
	@Test
	void pendingListReturnsOnlyOwnPendingOrdersNewestFirstAndExcludesOtherUsers() {
		User owner = createUser("pending-owner");
		createAccount(owner);
		User other = createUser("pending-other");
		createAccount(other);
		Instrument instrument = createCryptoInstrument("PENDOWN");

		// owner 보유 종목을 만들어 SELL 지정가도 함께 낼 수 있게 한다.
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
			com.finplay.api.account.domain.Market.CRYPTO, null, 100);

		assertThat(response.hasNext()).isFalse();
		assertThat(response.content()).allMatch(item -> item.status().equals("PENDING"));
		// containsExactly로 정확히 이 두 건만(순서까지) 포함됨을 단정하므로, other 사용자의 주문이 섞여 있지
		// 않다는 것도 함께 증명된다 — 별도 noneMatch 단정 없이 이 한 줄로 충분하다.
		assertThat(response.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(ownerSell.orderId(), ownerBuy.orderId());
		// PR #237 리뷰 차단 반영: 미체결 목록에서 limitPrice가 실제 걸어둔 값으로 노출되는지 확인한다
		// (매도·매수 각각 다른 지정가를 써서 우연히 같은 값으로 통과하는 것을 방지).
		assertThat(response.content().get(0).limitPrice()).isEqualByComparingTo(new BigDecimal("100000"));
		assertThat(response.content().get(1).limitPrice()).isEqualByComparingTo(new BigDecimal("10000000"));

		OrderListResponse otherResponse = orderService.getMyPendingOrders(other.getId(),
			com.finplay.api.account.domain.Market.CRYPTO, null, 100);
		assertThat(otherResponse.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(otherOrder.orderId());
	}

	// 시나리오 17: 목록에 있던 주문이 체결되거나 취소되면 이후 조회에서 더 이상 나타나지 않는다.
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
			com.finplay.api.account.domain.Market.CRYPTO, null, 100);
		assertThat(beforeTransition.content()).hasSize(3);

		limitOrderFillService.fillIfPending(toFill.orderId());
		limitOrderCancelService.cancelOrder(user.getId(), toCancel.orderId());

		OrderListResponse afterTransition = orderService.getMyPendingOrders(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO, null, 100);
		assertThat(afterTransition.content()).extracting(OrderListItemResponse::orderId)
			.containsExactly(remaining.orderId());
	}

	// 시나리오 18: limit보다 미체결 주문이 많으면 nextCursor로 이어받아 누락·중복 없이 전체를 순회할 수 있다.
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
			com.finplay.api.account.domain.Market.CRYPTO, 2);
		List<Long> singlePageIds = orderService
			.getMyPendingOrders(user.getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100)
			.content().stream().map(OrderListItemResponse::orderId).toList();

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(singlePageIds);
		assertThat(pagedIds).containsExactlyInAnyOrderElementsOf(createdIds);
	}

	// 시나리오 20(전반부): 매수 지정가 생성 직후 계좌 요약의 reservedCash가 예약 금액만큼 증가하고,
	// 취소 후에는 다시 0으로 돌아온다. cashBalance는 예약 전후 변하지 않는다.
	@Test
	void accountSummaryReservedCashReflectsReservationAndReturnsToZeroAfterCancel() {
		User user = createUser("reserved-cash");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("RESVCASH");

		AccountSummaryResponse before = accountService.getAccountSummary(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(before.reservedCash()).isZero();
		long cashBalanceBefore = before.cashBalance();

		LimitOrderResponse created = createLimitOrder(user, instrument, OrderSide.BUY,
			new BigDecimal("0.1"), new BigDecimal("10000000"), "idem-resvcash-buy");

		Account reservedAccount = accountRepository.findById(account.getId()).orElseThrow();
		long expectedReservedCash = reservedAccount.getReservedCash();
		assertThat(expectedReservedCash).isGreaterThan(0L);

		AccountSummaryResponse afterReserve = accountService.getAccountSummary(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(afterReserve.reservedCash()).isEqualTo(expectedReservedCash);
		assertThat(afterReserve.cashBalance()).isEqualTo(cashBalanceBefore);

		limitOrderCancelService.cancelOrder(user.getId(), created.orderId());

		AccountSummaryResponse afterCancel = accountService.getAccountSummary(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(afterCancel.reservedCash()).isZero();
		assertThat(afterCancel.cashBalance()).isEqualTo(cashBalanceBefore);
	}

	// 시나리오 20(후반부): 매도 지정가 생성 직후 보유 목록의 reservedQuantity가 예약 수량만큼 증가하고,
	// 체결 후에는 다시 0으로 돌아온다(전량 체결이라 quantity 자체가 매도분만큼 줄어든다).
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
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(beforeReserve).singleElement()
			.satisfies(h -> assertThat(h.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO));

		BigDecimal sellQuantity = new BigDecimal("4");
		LimitOrderResponse created = createLimitOrder(user, instrument, OrderSide.SELL,
			sellQuantity, new BigDecimal("100000"), "idem-resvqty-sell");

		List<HoldingListItemResponse> afterReserve = holdingService.getHoldings(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(afterReserve).singleElement().satisfies(h -> {
			assertThat(h.reservedQuantity()).isEqualByComparingTo(sellQuantity);
			assertThat(h.quantity()).isEqualByComparingTo(initialQuantity);
		});

		limitOrderFillService.fillIfPending(created.orderId());

		List<HoldingListItemResponse> afterFill = holdingService.getHoldings(user.getId(),
			com.finplay.api.account.domain.Market.CRYPTO);
		assertThat(afterFill).singleElement().satisfies(h -> {
			assertThat(h.reservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(h.quantity()).isEqualByComparingTo(initialQuantity.subtract(sellQuantity));
		});
	}

	// nextCursor를 따라 끝까지 페이지를 넘기며 orderId를 최신순 그대로 수집한다(OrderListIntegrationTest와 동일 관례).
	private List<Long> collectAllPendingOrderIdsByCursor(
		Long userId, com.finplay.api.account.domain.Market market, int limit) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20); // 무한루프 방지 안전장치.

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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
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
