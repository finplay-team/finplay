// fillBatch 청크 처리량이 배치 크기에 비례해 개선되는지 실측하는 벤치마크 — IDENTITY PK 때문에 Hibernate가
// Order/Trade/HoldingLot INSERT를 JDBC 배치로 묶지 못한다는 가설(이전 세션 코드 분석)을 실제 DB로 확인한다.
// CI 게이트가 아니라 수동 실측용이라 성능 임계값을 assert하지 않고 결과를 로그로만 남긴다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Slf4j
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LimitOrderFillBatchInsertThroughputBenchmarkTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 12, 0, 0);
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("100000");
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");

	private static final AtomicInteger USER_COUNTER = new AtomicInteger();

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
	private LimitOrderFillService limitOrderFillService;

	// JIT·커넥션 풀 워밍업을 먼저 소모해 이후 측정값이 콜드 스타트에 오염되지 않게 한다(과거 세션에서 재현된
	// MethodOrderer 기본값 문제와 같은 종류의 함정을 피하기 위해 @Order로 순서를 명시하고, 이 웜업 결과는
	// 측정값에 포함하지 않는다).
	@Test
	@Order(1)
	@DisplayName("웜업 — 측정 대상에 포함하지 않는다")
	void warmup() {
		measureFillBatch(5);
	}

	@Test
	@Order(2)
	@DisplayName("10건 청크 체결 소요시간 측정")
	void batchOf10() {
		measureFillBatch(10);
	}

	@Test
	@Order(3)
	@DisplayName("50건 청크 체결 소요시간 측정 (order.limit-fill-executor.batch-size 기본값)")
	void batchOf50() {
		measureFillBatch(50);
	}

	@Test
	@Order(4)
	@DisplayName("100건 청크 체결 소요시간 측정 — JDBC 배치가 실제로 작동한다면 10건 대비 건당 소요시간이 줄어야 한다")
	void batchOf100() {
		measureFillBatch(100);
	}

	// 각 건마다 별도 사용자·계좌를 만든다 — 같은 계좌·종목 보유(holding)를 여러 건이 동시에 새로 만드는
	// 실제 대량 체결 상황(서로 다른 사용자 다수가 같은 코인에 지정가를 걸어 두는 것)을 재현한다.
	private void measureFillBatch(int orderCount) {
		Instrument instrument = createCryptoInstrument("BENCH" + orderCount);
		List<Long> orderIds = new ArrayList<>(orderCount);
		for (int i = 0; i < orderCount; i++) {
			User user = createUser();
			createAccount(user);
			orderIds.add(createLimitOrder(user, instrument));
		}

		long startedAt = System.nanoTime();
		limitOrderFillService.fillBatch(orderIds);
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

		for (Long orderId : orderIds) {
			assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		}

		double perOrderMillis = orderCount == 0 ? 0 : (double)elapsedMillis / orderCount;
		log.info(
			"[BATCH-INSERT-BENCHMARK] orderCount={} totalMillis={} perOrderMillis={}",
			orderCount, elapsedMillis, String.format("%.2f", perOrderMillis));
	}

	private Long createLimitOrder(User user, Instrument instrument) {
		LimitOrderResponse response = limitOrderService.createLimitOrder(
			user.getId(), "idem-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));
		return response.orderId();
	}

	private User createUser() {
		String tag = "bench" + Long.toString(USER_COUNTER.incrementAndGet(), 36);
		return userRepository.saveAndFlush(
			User.create(tag + "@finplay.com", "password-hash", tag, NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true, NOW));
	}
}
