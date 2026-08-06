// 매수 파이프라인(이슈 #13)으로 생성한 실제 주문 데이터를 GET /api/orders 조회 서비스(market 필수·커서 페이지네이션)로 검증하는 통합 테스트다.
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
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderListResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

// AccountSummaryIntegrationTest(2026-07-30 agent-mistakes.md 항목)와 동일하게 instruments·stock_replay_sessions에
// saveAndFlush로 실제 커밋을 남기므로 @Transactional로 각 테스트 종료 시 롤백시켜 다른 테스트의 절대개수 단정을 지킨다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, OrderListIntegrationTest.FixedClockTestConfig.class})
class OrderListIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private OrderService orderService;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private Clock clock;

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
		((MutableClock)clock).set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@Test
	void getMyOrdersReturnsOwnOrdersNewestFirstWithFieldContractAndExcludesOtherUsers() {
		User owner = createUser("list-owner");
		createAccount(owner, com.finplay.api.account.domain.Market.STOCK);
		User other = createUser("list-other");
		createAccount(other, com.finplay.api.account.domain.Market.STOCK);
		Instrument instrument = createStockInstrument("LIST");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		// 10:00 시각 → 09:59 분봉(70000)이 체결가. owner 첫 주문, other 주문 순으로 같은 시각에 생성한다.
		orderService.createOrder(
			owner.getId(), "list-owner-idem-1", buyRequest(Market.STOCK, instrument.getId(), "10"));
		orderService.createOrder(
			other.getId(), "list-other-idem-1", buyRequest(Market.STOCK, instrument.getId(), "5"));

		// 10:01로 시각을 이동 → 10:00 분봉(80000)이 체결가. owner 두 번째(최신) 주문.
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(
			owner.getId(), "list-owner-idem-2", buyRequest(Market.STOCK, instrument.getId(), "20"));

		OrderListResponse response = orderService.getMyOrders(
			owner.getId(), com.finplay.api.account.domain.Market.STOCK, null, 100);
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

		// other 사용자의 주문은 owner 조회 결과에 포함되지 않는다.
		List<OrderListItemResponse> otherResult = orderService.getMyOrders(
			other.getId(), com.finplay.api.account.domain.Market.STOCK, null, 100).content();
		assertThat(otherResult).hasSize(1);
		assertThat(otherResult.get(0).quantity()).isEqualByComparingTo("5");
		assertThat(result).noneMatch(item -> item.orderId().equals(otherResult.get(0).orderId()));

		// 체결 전용 필드(tradeId·price·amount·fee·executedAt)는 OrderListItemResponse에 애초에 존재하지 않는다(타입 계약).
	}

	@Test
	void getMyOrdersReturnsEmptyListForUserWithNoOrders() {
		User newUser = createUser("list-no-orders");
		createAccount(newUser, com.finplay.api.account.domain.Market.STOCK);

		OrderListResponse response = orderService.getMyOrders(
			newUser.getId(), com.finplay.api.account.domain.Market.STOCK, null, 100);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyOrdersFiltersByMarketAndExcludesOtherMarketOrders() {
		User user = createUser("list-market");
		createAccount(user, com.finplay.api.account.domain.Market.STOCK);
		createAccount(user, com.finplay.api.account.domain.Market.CRYPTO);

		Instrument stockInstrument = createStockInstrument("MKST");
		createCandle(stockInstrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		orderService.createOrder(
			user.getId(), "list-market-stock-1", buyRequest(Market.STOCK, stockInstrument.getId(), "1"));

		Instrument cryptoInstrument = createCryptoInstrument("MKCO");
		seedCryptoPrice(cryptoInstrument, new BigDecimal("50000000"));
		orderService.createOrder(
			user.getId(), "list-market-crypto-1", buyRequest(Market.CRYPTO, cryptoInstrument.getId(), "0.01"));

		OrderListResponse stockResult = orderService.getMyOrders(
			user.getId(), com.finplay.api.account.domain.Market.STOCK, null, 100);
		OrderListResponse cryptoResult = orderService.getMyOrders(
			user.getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100);

		assertThat(stockResult.content()).hasSize(1);
		assertThat(stockResult.content().get(0).market()).isEqualTo("STOCK");
		assertThat(stockResult.content().get(0).instrumentId()).isEqualTo(stockInstrument.getId());

		assertThat(cryptoResult.content()).hasSize(1);
		assertThat(cryptoResult.content().get(0).market()).isEqualTo("CRYPTO");
		assertThat(cryptoResult.content().get(0).instrumentId()).isEqualTo(cryptoInstrument.getId());
	}

	// PR #237 리뷰 차단 반영 커버리지 공백 보완: LimitOrderPendingListIntegrationTest는 getMyPendingOrders(지정가만)
	// 경로에서만 limitPrice 실측값을 검증했고, 이 클래스의 기존 테스트들은 시장가 주문만 만들어 limitPrice를 전혀
	// 단정하지 않았다. GET /api/orders(getMyOrders)는 시장가·지정가가 섞여 나오는 유일한 경로이므로, 실제 DB에
	// 저장된 시장가 주문의 limitPrice가 null로, 지정가 주문(미체결)의 limitPrice가 실제 걸어둔 값으로 나오는지를
	// 여기서 함께 검증한다(mock이 아니라 OrderListItemResponse.from(Order)가 실제 엔티티를 그대로 반영하는지 확인).
	@Test
	void getMyOrdersExposesLimitPriceForLimitOrdersAndNullForMarketOrders() {
		User user = createUser("list-limitprice");
		createAccount(user, com.finplay.api.account.domain.Market.CRYPTO);
		Instrument cryptoInstrument = createCryptoInstrument("LPRICE");
		seedCryptoPrice(cryptoInstrument, new BigDecimal("50000000"));

		// 시장가 매수 — 즉시 체결되며 Order.limitPrice는 애초에 저장되지 않는다.
		orderService.createOrder(user.getId(), "list-limitprice-market",
			buyRequest(Market.CRYPTO, cryptoInstrument.getId(), "0.01"));

		// 지정가 매수 — 미체결 상태로 남아 걸어둔 가격이 그대로 노출돼야 한다.
		BigDecimal limitPrice = new BigDecimal("10000000");
		limitOrderService.createLimitOrder(user.getId(), "list-limitprice-limit",
			new LimitOrderCreateRequest(
				Market.CRYPTO, cryptoInstrument.getId(), OrderSide.BUY, new BigDecimal("0.01"), limitPrice));

		OrderListResponse response = orderService.getMyOrders(
			user.getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 100);
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
		createAccount(user, com.finplay.api.account.domain.Market.STOCK);
		Instrument instrument = createStockInstrument("PAGE");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));

		// 5건의 매수를 서로 다른 시각(분 단위 전진)에 체결시켜 requestedAt이 모두 달라지게 한다.
		for (int i = 1; i <= 5; i++) {
			((MutableClock)clock).set(BASE_NOW.plusMinutes(i));
			orderService.createOrder(user.getId(), "list-page-buy-" + i,
				buyRequest(Market.STOCK, instrument.getId(), String.valueOf(i)));
		}

		List<Long> pagedIds = collectAllOrderIdsByCursor(
			user.getId(), com.finplay.api.account.domain.Market.STOCK, 2);
		List<Long> singleCallIds = collectSinglePageOrderIds(
			user.getId(), com.finplay.api.account.domain.Market.STOCK, 100);

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(singleCallIds);
	}

	@Test
	void getMyOrdersRejectsWhenAccountForRequestedMarketDoesNotExist() {
		User user = createUser("list-no-crypto-acct");
		// STOCK 계좌만 만들고 CRYPTO 계좌는 만들지 않는다 — 존재하지 않는 계좌의 market으로 조회하는 시나리오.
		createAccount(user, com.finplay.api.account.domain.Market.STOCK);

		assertThatThrownBy(() -> orderService.getMyOrders(
			user.getId(), com.finplay.api.account.domain.Market.CRYPTO, null, 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(
				exception -> assertThat(((BusinessException)exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	// limit보다 데이터가 많을 때 nextCursor를 따라 끝까지 페이지를 넘기며 orderId를 최신순 그대로 수집한다.
	// 마지막으로 fetch한 페이지는 hasNext=false·nextCursor=null이어야 한다(루프를 빠져나오는 조건 자체가 이를 보장).
	private List<Long> collectAllOrderIdsByCursor(Long userId, com.finplay.api.account.domain.Market market,
		int limit) {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		boolean hasNext = true;
		int pageCount = 0;
		while (hasNext) {
			pageCount++;
			assertThat(pageCount).isLessThanOrEqualTo(20); // 무한루프 방지 안전장치.

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

	// 커서 없이 한 번에 큰 limit으로 조회해 전체 orderId를 최신순 그대로 수집한다(페이지 결과와 비교하는 기준선).
	private List<Long> collectSinglePageOrderIds(Long userId, com.finplay.api.account.domain.Market market, int limit) {
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

	private Account createAccount(User user, com.finplay.api.account.domain.Market market) {
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

	// 현재 clock 시각에 맞춰 최신 틱을 저장한다 — PriceStore.isStale은 10초 임계값으로 판정하므로 항상 현재 시각을 써야 한다.
	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	// nickname 컬럼은 VARCHAR(50)(V2 마이그레이션)이라 UUID 전체(32자)를 붙이면 시나리오명이 길 때 초과한다
	// (예: "list-no-crypto-acct" 20자 + "-" + 32자 = 53자 → MysqlDataTruncation). 8자로 줄여 여유를 둔다.
	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	// 전역 Clock 빈(ClockConfig, Asia/Seoul 실시각)을 이 테스트 컨텍스트에서만 고정 시각으로 교체한다.
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new MutableClock(BASE_NOW.atZone(KST).toInstant(), KST);
		}
	}

	// 최신순 정렬 검증을 위해 서로 다른 시각에 주문을 만들 수 있도록 시각을 전진시킬 수 있는 Clock 구현.
	private static final class MutableClock extends Clock {

		private final ZoneId zone;
		private volatile Instant instant;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(zone).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
