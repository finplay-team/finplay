// 054-limit-order-fill-bulk-lock 위험 요소 2 — 같은 계좌가 같은 청크·같은 신규 종목에 지정가 매수를 2건 이상
// 걸어둔 경우, holding 벌크 preflight가 스냅숏이 아니라 청크 내에서 즉시 갱신되는 가변 맵이라 두 번째 INSERT를
// 시도하지 않는지(uk_holdings_account_instrument 유니크 제약 위반 없음) Testcontainers로 검증한다.
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
		// holding이 아직 없는 신규 종목이어야 위험 요소 2(같은 청크 안 중복 INSERT 시도)가 재현된다.
		Instrument instrument = createCryptoInstrument("DUPHOLD");
		BigDecimal limitPrice = new BigDecimal("100000");
		BigDecimal firstQuantity = new BigDecimal("0.1");
		BigDecimal secondQuantity = new BigDecimal("0.2");

		Long first = createLimitOrder(user, instrument, limitPrice, firstQuantity, "dup-holding-1");
		Long second = createLimitOrder(user, instrument, limitPrice, secondQuantity, "dup-holding-2");

		// 같은 청크(fillBatch 호출 1번) 안에 두 주문을 함께 넣는다 — 청크 시작 시 holding 벌크 조회는 아직
		// 존재하지 않는 이 holding을 빈 결과로 돌려주므로, 맵을 즉시 갱신하지 않으면 두 번째 처리가 다시
		// Holding.create()를 시도해 uk_holdings_account_instrument 유니크 제약을 위반한다.
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
