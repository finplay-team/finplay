// 2단계 대본에서 미체결 → 취소 → 재접수 → 체결까지 이어지는 핵심 장면을 끝까지 도는 통합 테스트
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 049 tasks 6번 — spec의 핵심 장면(ORDERBASICS-012·013·014)을 끝까지 돈다.
 *
 * <p>시장가 왕복(2단계 첫 자리) → 안내 범위(90,000~110,000원) 밖 지정가 매도 접수 성공(ORDERBASICS-012,
 * 대본 극값 88,000~112,000원 안에서도 결코 닿지 않는 130,000원) → 여러 tick을 돌려도 미체결 유지 → 취소는
 * 언제나 자유롭다(ORDERBASICS-013) → 범위 안 108,000원으로 재접수 → 사인파가 다음 바퀴 안에 그 값을
 * 다시 지나가 체결된다(ORDERBASICS-014).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeOrderBasicsFullJourneyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 9, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	// 대본 극값은 88,000~112,000원이라 130,000원은 어떤 가상 분에도 닿지 않는다 — 결코 체결되지 않는 값이다.
	private static final BigDecimal OUT_OF_RANGE_SELL_PRICE = new BigDecimal("130000");
	// 안내 범위(90,000~110,000원) 안이며, 대본 사인파가 한 바퀴(가상 20분)마다 지나가는 값이다.
	private static final BigDecimal IN_RANGE_SELL_PRICE = new BigDecimal("108000");
	// tick 한 번이 소비하는 상한(PracticeScenarioProgressService.MAX_TICK_GAP_SECONDS)이다.
	private static final int SECONDS_PER_TICK = 30;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private OrderService orderService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private LimitOrderCancelService limitOrderCancelService;
	@Autowired
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void unfilledLimitOrderCanBeCancelledAndReplacedInsideTheGuideRangeUntilItFills() {
		Fixture fixture = orderBasicsRun("ob-full-journey");

		// 시장가 왕복 — 2단계의 첫 자리, 지정가 게이트를 여는 조건이다(ORDERBASICS-015).
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();

		// 다음 진입 — 지정가 매도로 청산할 포지션을 만든다.
		clock.set(BASE_NOW.plusSeconds(3));
		buy(fixture);

		// 범위 밖 지정가 매도가 접수된다(ORDERBASICS-012) — 접수 자체는 막지 않는다.
		clock.set(BASE_NOW.plusSeconds(4));
		LimitOrderResponse outOfRangeOrder = sellLimit(fixture, OUT_OF_RANGE_SELL_PRICE);
		assertThat(outOfRangeOrder.status()).isEqualTo("PENDING");

		// 여러 tick을 돌려도 미체결로 남는다 — 대본이 결코 130,000원에 닿지 않는다.
		tick(fixture, BASE_NOW.plusSeconds(5));
		tick(fixture, BASE_NOW.plusSeconds(5 + SECONDS_PER_TICK));
		tick(fixture, BASE_NOW.plusSeconds(5 + (long)SECONDS_PER_TICK * 2));
		assertThat(orderStatus(outOfRangeOrder.orderId())).isEqualTo(OrderStatus.PENDING);

		// 취소는 언제나 자유롭다(ORDERBASICS-013).
		limitOrderCancelService.cancelOrder(fixture.userId(), outOfRangeOrder.orderId());
		assertThat(orderStatus(outOfRangeOrder.orderId())).isEqualTo(OrderStatus.CANCELLED);

		// 범위 안으로 재접수한다.
		LimitOrderResponse inRangeOrder = sellLimit(fixture, IN_RANGE_SELL_PRICE);
		assertThat(inRangeOrder.status()).isEqualTo("PENDING");

		// 다음 바퀴 안에 체결 기회를 얻는다(ORDERBASICS-014) — 사인파가 20가상분마다 108,000원 이상을 지난다.
		long tickAt = 5 + (long)SECONDS_PER_TICK * 2;
		for (int round = 1; round <= 4 && orderStatus(inRangeOrder.orderId()) == OrderStatus.PENDING; round++) {
			tickAt += SECONDS_PER_TICK;
			tick(fixture, BASE_NOW.plusSeconds(tickAt));
		}

		assertThat(orderStatus(inRangeOrder.orderId())).isEqualTo(OrderStatus.FILLED);
		Order filled = orderRepository.findById(inRangeOrder.orderId()).orElseThrow();
		assertThat(filled.getSide()).isEqualTo(OrderSide.SELL);
	}

	private void tick(Fixture fixture, LocalDateTime at) {
		clock.set(at);
		chartService.tick(fixture.userId(), Market.CRYPTO);
	}

	private boolean marketRoundTripCompleted(Fixture fixture) {
		return stageProgressCalculationService
			.calculate(attemptRepository.findById(fixture.attemptId()).orElseThrow())
			.marketBuySellCompleted();
	}

	private OrderStatus orderStatus(Long orderId) {
		return orderRepository.findById(orderId).orElseThrow().getStatus();
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-journey-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-journey-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private LimitOrderResponse sellLimit(Fixture fixture, BigDecimal limitPrice) {
		return limitOrderService.createLimitOrder(fixture.userId(), "ob-journey-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, QUANTITY, limitPrice));
	}

	/**
	 * 종목 선택만으로 대본이 정해진다 — 049 tasks 5번이 진입 대본을 다시 {@code firstScriptId(market)}로
	 * 열어 두었으므로 CRYPTO 진입은 {@code CRYPTO_ORDER_BASICS_V1}이다(전환 엔드포인트 없이도 이 테스트가
	 * 그 대본을 만난다).
	 */
	private Fixture orderBasicsRun(String scenario) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + suffix, scenario, BigDecimal.ONE, 0L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
