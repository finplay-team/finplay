package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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
class LimitOrderFillBatchDuplicateHoldingIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

	@Test
	@DisplayName("같은 계좌가 같은 청크에서 같은 신규 종목에 지정가 매수 2건을 걸면 유니크 제약 위반 없이 둘 다 체결되고 holding은 하나로 합산된다")
	void fillBatchMergesTwoNewBuyOrdersOfSameAccountAndInstrumentIntoOneHolding() {
		User user = createUser("dup-holding");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("DUPHOLD");
		BigDecimal limitPrice = new BigDecimal("100000");
		BigDecimal firstQuantity = new BigDecimal("0.1");
		BigDecimal secondQuantity = new BigDecimal("0.2");

		Long first = createLimitOrder(user, instrument, limitPrice, firstQuantity, "dup-holding-1");
		Long second = createLimitOrder(user, instrument, limitPrice, secondQuantity, "dup-holding-2");

		assertThatCode(() -> limitOrderFillService.fillBatch(List.of(first, second))).doesNotThrowAnyException();

		assertThat(orderRepository.findById(first).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(orderRepository.findById(second).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);

		List<Holding> holdings = holdingRepository.findByAccountId(account.getId());
		assertThat(holdings).hasSize(1);
		assertThat(holdings.get(0).getQuantity()).isEqualByComparingTo(firstQuantity.add(secondQuantity));
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
