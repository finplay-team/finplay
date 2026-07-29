// 매수 파이프라인(이슈 #13)으로 생성한 실제 주문 데이터를 GET /api/orders 조회 서비스로 검증하는 통합 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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

@SpringBootTest
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

	@BeforeEach
	void setUp() {
		((MutableClock)clock).set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void getMyOrdersReturnsOwnOrdersNewestFirstWithFieldContractAndExcludesOtherUsers() {
		User owner = createUser("list-owner");
		Account ownerAccount = createAccount(owner);
		User other = createUser("list-other");
		Account otherAccount = createAccount(other);
		Instrument instrument = createStockInstrument("LIST");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));

		// 10:00 시각 → 09:59 분봉(70000)이 체결가. owner 첫 주문, other 주문 순으로 같은 시각에 생성한다.
		orderService.createOrder(
			owner.getId(), "list-owner-idem-1", buyRequest(instrument.getId(), "10"));
		orderService.createOrder(
			other.getId(), "list-other-idem-1", buyRequest(instrument.getId(), "5"));

		// 10:01로 시각을 이동 → 10:00 분봉(80000)이 체결가. owner 두 번째(최신) 주문.
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(
			owner.getId(), "list-owner-idem-2", buyRequest(instrument.getId(), "20"));

		List<OrderListItemResponse> result = orderService.getMyOrders(owner.getId());

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
		List<OrderListItemResponse> otherResult = orderService.getMyOrders(other.getId());
		assertThat(otherResult).hasSize(1);
		assertThat(otherResult.get(0).quantity()).isEqualByComparingTo("5");
		assertThat(result).noneMatch(item -> item.orderId().equals(otherResult.get(0).orderId()));

		// 체결 전용 필드(tradeId·price·amount·fee·executedAt)는 OrderListItemResponse에 애초에 존재하지 않는다(타입 계약).
	}

	@Test
	void getMyOrdersReturnsEmptyListForUserWithNoOrders() {
		User newUser = createUser("list-no-orders");
		createAccount(newUser);

		List<OrderListItemResponse> result = orderService.getMyOrders(newUser.getId());

		assertThat(result).isEmpty();
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
