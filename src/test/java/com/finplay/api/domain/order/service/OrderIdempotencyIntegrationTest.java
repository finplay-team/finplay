// 동일 Idempotency-Key 재요청 응답 재현·다른 본문 409·서로 다른 사용자 간 무간섭을 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
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
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class OrderIdempotencyIntegrationTest {

	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);

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

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void replayingSameKeyAndBodyForBuyReturnsIdenticalResponseWithoutAddingRows() {
		User user = createUser("idem-buy-replay");
		createAccount(user);
		Instrument instrument = createStockInstrument("IDMBUY");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		OrderResponse first = orderService.createOrder(
			user.getId(), "idem-key-buy-replay", buyRequest(instrument.getId(), "10"));

		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		long holdingsBefore = holdingRepository.count();
		long holdingLotsBefore = holdingLotRepository.count();

		OrderResponse second = orderService.createOrder(
			user.getId(), "idem-key-buy-replay", buyRequest(instrument.getId(), "10"));

		assertReplayIsIdenticalToFirstResponse(second, first);
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(holdingRepository.count()).isEqualTo(holdingsBefore);
		assertThat(holdingLotRepository.count()).isEqualTo(holdingLotsBefore);
	}

	@Test
	void replayingSameKeyAndBodyForSellReturnsIdenticalResponseWithoutAddingRows() {
		User user = createUser("idem-sell-replay");
		createAccount(user);
		Instrument instrument = createStockInstrument("IDMSELL");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		orderService.createOrder(user.getId(), "idem-key-sell-buy", buyRequest(instrument.getId(), "10"));

		OrderResponse first = orderService.createOrder(
			user.getId(), "idem-key-sell-replay", sellRequest(instrument.getId(), "5"));

		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();
		long holdingsBefore = holdingRepository.count();
		long holdingLotsBefore = holdingLotRepository.count();

		OrderResponse second = orderService.createOrder(
			user.getId(), "idem-key-sell-replay", sellRequest(instrument.getId(), "5"));

		assertReplayIsIdenticalToFirstResponse(second, first);
		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
		assertThat(holdingRepository.count()).isEqualTo(holdingsBefore);
		assertThat(holdingLotRepository.count()).isEqualTo(holdingLotsBefore);
	}

	@Test
	void sameKeyWithDifferentBodyIsRejectedWithIdempotencyConflict() {
		User user = createUser("idem-conflict");
		createAccount(user);
		Instrument instrument = createStockInstrument("IDMCONF");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		orderService.createOrder(user.getId(), "idem-key-conflict", buyRequest(instrument.getId(), "10"));

		long ordersBefore = orderRepository.count();
		long tradesBefore = tradeRepository.count();

		assertThatThrownBy(() -> orderService.createOrder(
			user.getId(), "idem-key-conflict", buyRequest(instrument.getId(), "20")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));

		assertThat(orderRepository.count()).isEqualTo(ordersBefore);
		assertThat(tradeRepository.count()).isEqualTo(tradesBefore);
	}

	@Test
	void differentUsersReusingSameIdempotencyKeyEachExecuteIndependently() {
		User userA = createUser("idem-user-a");
		createAccount(userA);
		User userB = createUser("idem-user-b");
		createAccount(userB);
		Instrument instrument = createStockInstrument("IDMUSR");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		String sharedKey = "idem-key-shared";
		OrderResponse responseA = orderService.createOrder(
			userA.getId(), sharedKey, buyRequest(instrument.getId(), "10"));
		OrderResponse responseB = orderService.createOrder(
			userB.getId(), sharedKey, buyRequest(instrument.getId(), "10"));

		assertThat(responseA.orderId()).isNotEqualTo(responseB.orderId());
		assertThat(orderRepository.findById(responseA.orderId())).isPresent();
		assertThat(orderRepository.findById(responseB.orderId())).isPresent();
		assertThat(tradeRepository.findById(responseA.tradeId())).isPresent();
		assertThat(tradeRepository.findById(responseB.tradeId())).isPresent();
	}

	// DB에서 재조회한 quantity/price는 컬럼 scale(DECIMAL(20,8))이 적용돼 최초 응답의 BigDecimal과
	// equals()가 아닌 isEqualByComparingTo() 기준으로만 같다 — record 전체 equals 대신 필드별로 비교한다.
	private void assertReplayIsIdenticalToFirstResponse(OrderResponse replay, OrderResponse first) {
		assertThat(replay.orderId()).isEqualTo(first.orderId());
		assertThat(replay.market()).isEqualTo(first.market());
		assertThat(replay.instrumentId()).isEqualTo(first.instrumentId());
		assertThat(replay.side()).isEqualTo(first.side());
		assertThat(replay.orderType()).isEqualTo(first.orderType());
		assertThat(replay.status()).isEqualTo(first.status());
		assertThat(replay.quantity()).isEqualByComparingTo(first.quantity());
		assertThat(replay.requestedAt()).isEqualTo(first.requestedAt());
		assertThat(replay.tradeId()).isEqualTo(first.tradeId());
		assertThat(replay.price()).isEqualByComparingTo(first.price());
		assertThat(replay.amount()).isEqualTo(first.amount());
		assertThat(replay.fee()).isEqualTo(first.fee());
		assertThat(replay.realizedPnl()).isEqualTo(first.realizedPnl());
		assertThat(replay.executedAt()).isEqualTo(first.executedAt());
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
