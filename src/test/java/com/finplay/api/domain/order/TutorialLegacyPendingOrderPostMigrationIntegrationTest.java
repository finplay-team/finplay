package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TutorialLegacyPendingOrderPostMigrationIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final long RESERVED_CASH = 100_050L;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private LimitOrderFillService limitOrderFillService;
	@Autowired
	private LimitOrderCancelService limitOrderCancelService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void tearDown() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM trades WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		instrumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", id));
		userIds.clear();
		instrumentIds.clear();
	}

	@Test
	void fillingLegacyPendingBuyOrderThrowsBecauseReservationStayedOnRealAccount() {
		User user = createUser("legacy-fill");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("legacy-fill");
		Order legacyOrder = seedLegacyPendingBuyOrder(user, account, instrument);

		assertThatThrownBy(() -> limitOrderFillService.fillIfPending(legacyOrder.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 확정할 수 없습니다");

		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash())
			.isEqualTo(RESERVED_CASH);
		assertThat(orderRepository.findById(legacyOrder.getId()).orElseThrow().getStatus().name())
			.isEqualTo("PENDING");
	}

	@Test
	void cancellingLegacyPendingBuyOrderThrowsForTheSameReason() {
		User user = createUser("legacy-cancel");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("legacy-cancel");
		Order legacyOrder = seedLegacyPendingBuyOrder(user, account, instrument);

		assertThatThrownBy(() -> limitOrderCancelService.cancelOrder(user.getId(), legacyOrder.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 해제할 수 없습니다");

		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash())
			.isEqualTo(RESERVED_CASH);
		assertThat(orderRepository.findById(legacyOrder.getId()).orElseThrow().getStatus().name())
			.isEqualTo("PENDING");
	}

	private Order seedLegacyPendingBuyOrder(User user, Account account, Instrument instrument) {
		account.reserveCash(RESERVED_CASH);
		accountRepository.saveAndFlush(account);
		return orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"legacy-" + UUID.randomUUID(), "a".repeat(64), NOW));
	}

	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
		userIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + shortRandom(), scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		return instrument;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + shortRandom() + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + shortRandom();
	}

	private static String shortRandom() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
