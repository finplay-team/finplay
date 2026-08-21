// 동시 다발 가격 틱 아래에서 ADR-0024의 비동기 체결이 종목 내부 순서를 지키고 중복 없이 완료되며 피드 스레드가 체결을 기다리지 않는지 증명하는 통합 테스트다.
package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0024(지정가 체결을 종목별 직렬화 실행기로 위임하는 결정)이 실제 동시성 아래에서도 지키는 세 가지를 한
 * 시나리오로 함께 증명한다.
 *
 * <ol>
 *   <li><b>비차단</b>: 여러 스레드가 동시에 같은 종목의 {@code CryptoPriceUpdatedEvent}를 발행해도(=피드 스레드
 *       역할), 각 리스너 호출은 체결 완료를 기다리지 않고 후보 조회 + 실행기 제출만 한 뒤 빠르게 반환한다.</li>
 *   <li><b>순서 보존</b>: 같은 종목의 지정가는 "먼저 건 사람이 먼저 체결"(요청 순서 = requestedAt asc, id asc)이
 *       동시 다발 틱 아래에서도 그대로 지켜진다.</li>
 *   <li><b>중복 없음</b>: 동시 틱이 같은 후보를 중복으로 실행기에 제출해도({@code fillIfPending}의 PENDING
 *       재확인 덕에) 각 주문은 정확히 1건의 {@code Trade}만 만든다.</li>
 * </ol>
 *
 * <p>이벤트는 {@code PriceStore.saveTick}이 아니라 {@link ApplicationEventPublisher}로 직접 발행한다.
 * {@code PriceStore}는 "같은 심볼의 과거·동시각 틱은 최신 틱을 덮어쓰지 못한다"는 별도의 stale 완화 규칙이
 * 있어(수신시각이 엄격히 더 나중일 때만 이벤트를 발행), 이 테스트가 의도하는 "정말로 동시에 도착한 여러 틱이
 * 각자 리스너를 트리거하는" 상황과 목적이 다르다 — 이 테스트가 검증하려는 것은 리스너·실행기의 동시성이지
 * PriceStore의 중복 틱 억제가 아니다.
 *
 * <p>실제 운영에서는 피드 스레드가 하나뿐이지만(빗썸 웹소켓 세션의 메시지 콜백은 동시 호출되지 않는다), 라우터의
 * 종목별 직렬화가 "여러 스레드가 후보를 겹쳐 제출해도" 안전한지까지 증명하기 위해 일부러 더 가혹한 조건(다중
 * 스레드 동시 이벤트 발행)으로 재현한다 — 더 약한 전제(피드 스레드 단일)에서도 당연히 성립한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderAsyncFillConcurrencyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 12, 0, 0);

	private static final int ORDER_COUNT = 15;

	private static final int CONCURRENT_TICK_COUNT = 4;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("동시 다발 가격 틱 아래에서도 지정가는 요청 순서대로 중복 없이 체결되고 틱 처리 자체는 체결을 기다리지 않는다")
	void concurrentPriceTicksFillEveryPendingOrderExactlyOnceInRequestOrderWithoutBlockingTheFeedThread()
		throws Exception {
		User user = createUser("async-fill");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("ASYNCFIL");

		BigDecimal limitPrice = new BigDecimal("100000");
		// amount = 0.1 * 100,000 = 10,000/건 — 코인 최소 주문금액(instrument.minOrderAmount=5,000)을 넘기면서도
		// ORDER_COUNT(15)건 합계(150,000)가 계좌 기본 현금(10,000,000)에 비해 미미해 현금 부족으로 생성이
		// 거부될 일이 없다.
		BigDecimal quantity = new BigDecimal("0.1");
		List<Long> orderIdsInRequestOrder = new ArrayList<>();
		for (int i = 0; i < ORDER_COUNT; i++) {
			LimitOrderResponse response = limitOrderService.createLimitOrder(
				user.getId(), "idem-async-fill-" + i,
				new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
			orderIdsInRequestOrder.add(response.orderId());
		}
		// 생성이 이 테스트 스레드에서 순차로 일어나므로 생성 순서 = id 오름차순 = requestedAt 오름차순이다 —
		// idx_orders_limit_fill의 정렬 계약과 같은 축이라 이 순서를 그대로 "기대하는 체결 순서"로 쓴다.

		long tickWallClockMillis = fireConcurrentPriceTickEvents(instrument.getSymbol(), limitPrice);

		// 피드 스레드 역할의 리스너 호출은 체결 자체를 기다리지 않고 후보 조회 + 실행기 제출만 하고 반환하므로,
		// 15건의 체결을 실제로 처리하는 데 걸릴 시간보다 훨씬 짧게 끝나야 한다.
		assertThat(tickWallClockMillis)
			.as("가격 틱 처리(후보 조회+실행기 제출)가 체결 완료를 기다리지 않고 빠르게 반환됐다")
			.isLessThan(2000L);

		awaitUntil(
			() -> orderIdsInRequestOrder.stream()
				.allMatch(
					orderId -> orderRepository.findById(orderId).orElseThrow().getStatus() == OrderStatus.FILLED),
			Duration.ofSeconds(10), "모든 주문이 제한 시간 안에 체결되지 않았다");

		// 중복 체결 없음 — 동시 틱이 같은 후보를 여러 번 재조회해 실행기에 중복 제출해도 fillIfPending의 PENDING
		// 재확인 덕에 두 번째부터는 no-op이라 각 주문당 정확히 1건의 Trade만 남아야 한다.
		for (Long orderId : orderIdsInRequestOrder) {
			assertThat(countTradesForOrder(orderId))
				.as("orderId=%d의 Trade가 정확히 1건이어야 한다", orderId)
				.isEqualTo(1L);
		}

		// 종목 내부 순서 보존 — trades.id는 자동증가라 실제 체결(삽입) 순서를 그대로 반영한다. 동시 다발 틱
		// 아래에서도 요청 순서(=requestedAt asc, id asc)와 실제 체결 순서가 같아야 한다(ADR-0024 §결정1).
		List<Long> tradeIdsInRequestOrder = tradeIdsForOrdersInGivenOrder(orderIdsInRequestOrder);
		assertThat(tradeIdsInRequestOrder).as("요청 순서대로 나열한 Trade id가 오름차순이어야 실제 체결 순서와 일치한다")
			.isSorted();
	}

	// CONCURRENT_TICK_COUNT개 스레드가 배리어로 겹침을 만든 뒤 같은 종목의 가격 갱신 이벤트를 동시에 발행한다.
	// 반환값은 이 스레드들이 전부 끝나는 데 걸린 실제 시간(ms)이다.
	private long fireConcurrentPriceTickEvents(String symbol, BigDecimal price) throws Exception {
		CyclicBarrier atTheGate = new CyclicBarrier(CONCURRENT_TICK_COUNT);
		ExecutorService tickExecutor = Executors.newFixedThreadPool(CONCURRENT_TICK_COUNT);
		try {
			long startedAt = System.nanoTime();
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_TICK_COUNT; i++) {
				futures.add(tickExecutor.submit(() -> {
					awaitAtGate(atTheGate);
					eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, NOW, NOW));
				}));
			}
			for (Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
			return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		} finally {
			tickExecutor.shutdownNow();
			assertThat(tickExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private List<Long> tradeIdsForOrdersInGivenOrder(List<Long> orderIdsInRequestOrder) {
		List<Long> tradeIds = new ArrayList<>();
		for (Long orderId : orderIdsInRequestOrder) {
			Long tradeId = jdbcTemplate
				.queryForObject("SELECT id FROM trades WHERE order_id = ?", Long.class, orderId);
			tradeIds.add(tradeId);
		}
		return tradeIds;
	}

	private long countTradesForOrder(Long orderId) {
		Long count = jdbcTemplate
			.queryForObject("SELECT COUNT(*) FROM trades WHERE order_id = ?", Long.class, orderId);
		return count == null ? 0L : count;
	}

	private static void awaitAtGate(CyclicBarrier barrier) {
		try {
			barrier.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("배리어 대기 중 인터럽트", e);
		} catch (Exception e) {
			throw new IllegalStateException("배리어에서 모이지 못했다", e);
		}
	}

	// CryptoCardPushFanoutIntegrationTest의 awaitUntil 관례를 그대로 따른다.
	private static void awaitUntil(
		java.util.function.BooleanSupplier condition, Duration timeout, String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
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
