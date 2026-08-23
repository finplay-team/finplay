// 054-limit-order-fill-bulk-lock 완료 조건 — 개선 전후 처리 시간 실측 비교. ADR-0024가 원래 재현했던 조건과
// 같은 규모(계좌 15개 풀, 단일 종목·단일 가격 지정가 500건)로 fillBatch를 batch-size(50) 단위 청크로 나눠
// 호출한 총 소요시간을 측정한다. CI 게이트가 아니라 수동 실측용이라 성능 임계값을 assert하지 않고 결과를
// 로그로만 남긴다 — 결과는 docs/loadtest/limit-order-fill-batch-bulk-lock-benchmark-result.md에 기록한다.
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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Slf4j
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillBulkLockAccountPoolBenchmarkTest {

	// ai/adr/0024-limit-order-fill-executor.md가 재현에 쓴 조건과 같은 규모("계좌 15개 풀 조건에서 가격 하나에
	// 지정가 500건").
	private static final int ACCOUNT_POOL_SIZE = 15;
	private static final int ORDER_COUNT = 500;
	// order.limit-fill-executor.batch-size 기본값(application.yml) — 실제 파티션 워커가 청크를 나누는 단위와
	// 맞춰야 "적용 전/후" 비교가 실제 운영 조건을 반영한다.
	private static final int CHUNK_SIZE = 50;

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("100000");
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");

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

	@Test
	@DisplayName("계좌풀 15개·단일 종목·단일 가격 지정가 500건을 batch-size(50) 청크로 체결하는 총 소요시간을 측정한다")
	void accountPoolFillBatchThroughput() {
		Instrument instrument = createCryptoInstrument("BULKLOCK");
		List<Account> accountPool = createAccountPool(ACCOUNT_POOL_SIZE);

		List<Long> orderIds = new ArrayList<>(ORDER_COUNT);
		for (int i = 0; i < ORDER_COUNT; i++) {
			Account account = accountPool.get(i % ACCOUNT_POOL_SIZE);
			orderIds.add(createLimitOrder(account, instrument));
		}

		long startedAt = System.nanoTime();
		for (int from = 0; from < orderIds.size(); from += CHUNK_SIZE) {
			int to = Math.min(from + CHUNK_SIZE, orderIds.size());
			limitOrderFillService.fillBatch(orderIds.subList(from, to));
		}
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

		for (Long orderId : orderIds) {
			assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		}

		double perOrderMillis = (double)elapsedMillis / ORDER_COUNT;
		log.info(
			"[BULK-LOCK-BENCHMARK] accountPoolSize={} orderCount={} chunkSize={} totalMillis={} perOrderMillis={}",
			ACCOUNT_POOL_SIZE, ORDER_COUNT, CHUNK_SIZE, elapsedMillis, String.format("%.2f", perOrderMillis));
	}

	private List<Account> createAccountPool(int size) {
		List<Account> accounts = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			User user = createUser("pool" + i);
			accounts.add(accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW)));
		}
		return accounts;
	}

	private Long createLimitOrder(Account account, Instrument instrument) {
		LimitOrderResponse response = limitOrderService.createLimitOrder(
			account.getUser().getId(), "idem-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));
		return response.orderId();
	}

	private User createUser(String tag) {
		return userRepository.saveAndFlush(
			User.create(tag + "-" + UUID.randomUUID() + "@finplay.com", "password-hash", tag, NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true, NOW));
	}
}
