package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderFillBatchAtomicityIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 12, 0, 0);

	private static final Long NONEXISTENT_ORDER_ID = Long.MAX_VALUE;

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
	@DisplayName("청크 안의 모든 주문이 유효하면 한 트랜잭션으로 함께 FILLED된다")
	void fillBatchCommitsEveryValidOrderInTheChunkTogether() {
		User user = createUser("batch-happy");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("BATCHHAPPY");
		BigDecimal limitPrice = new BigDecimal("100000");
		BigDecimal quantity = new BigDecimal("0.1");

		Long first = createLimitOrder(user, instrument, limitPrice, quantity, "batch-happy-1");
		Long second = createLimitOrder(user, instrument, limitPrice, quantity, "batch-happy-2");
		Long third = createLimitOrder(user, instrument, limitPrice, quantity, "batch-happy-3");

		limitOrderFillService.fillBatch(List.of(first, second, third));

		assertThat(orderRepository.findById(first).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(orderRepository.findById(second).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(orderRepository.findById(third).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@DisplayName("청크 안의 한 건이 존재하지 않으면 청크 전체가 롤백되어 나머지도 PENDING으로 남는다(ADR-0025 §결정3)")
	void fillBatchRollsBackTheWholeChunkWhenOneOrderIdIsInvalid() {
		User user = createUser("batch-fail");
		createAccount(user);
		Instrument instrument = createCryptoInstrument("BATCHFAIL");
		BigDecimal limitPrice = new BigDecimal("100000");
		BigDecimal quantity = new BigDecimal("0.1");

		Long valid1 = createLimitOrder(user, instrument, limitPrice, quantity, "batch-fail-1");
		Long valid2 = createLimitOrder(user, instrument, limitPrice, quantity, "batch-fail-2");

		assertThatThrownBy(() -> limitOrderFillService.fillBatch(List.of(valid1, NONEXISTENT_ORDER_ID, valid2)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("체결 대상 주문을 찾을 수 없습니다");

		assertThat(orderRepository.findById(valid1).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(valid2).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
	}

	private Long createLimitOrder(
		User user, Instrument instrument, BigDecimal limitPrice, BigDecimal quantity, String idempotencyKey) {
		LimitOrderResponse response = limitOrderService.createLimitOrder(
			user.getId(), "idem-" + idempotencyKey,
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		return response.orderId();
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
