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

	private static final int ACCOUNT_POOL_SIZE = 15;
	private static final int ORDER_COUNT = 500;
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
