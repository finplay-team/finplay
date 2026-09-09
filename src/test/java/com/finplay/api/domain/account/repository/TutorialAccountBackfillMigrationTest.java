package com.finplay.api.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TutorialAccountBackfillMigrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final String MIGRATION_PATH = "/db/migration/V46__create_tutorial_accounts_and_backfill_cash.sql";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private void runBackfillUpdate() {
		entityManager.flush();
		for (String statement : readBackfillUpdateStatements()) {
			jdbcTemplate.execute(statement);
		}
		entityManager.clear();
	}

	private List<String> readBackfillUpdateStatements() {
		try {
			String sql = StreamUtils.copyToString(
				new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);
			String withoutComments = Arrays.stream(sql.split("\n"))
				.filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
			return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(statement -> !statement.isEmpty())
				.filter(statement -> statement.toUpperCase().startsWith("UPDATE"))
				.filter(statement -> !statement.toLowerCase().contains("sandbox_cash_adjustment"))
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("V46 마이그레이션 파일을 읽을 수 없습니다.", e);
		}
	}

	@Test
	@DisplayName("배포 시점에 이미 존재하는 샌드박스 지정가 매수 PENDING 주문은 취소되고 실제 계좌 예약이 반환된다")
	void backfillCancelsLegacyPendingSandboxBuyOrdersAndReturnsRealAccountReservation() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-order@finplay.com", "hash", "v46legacy", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-buy");
		account.reserveCash(100_050L);
		accountRepository.saveAndFlush(account);
		Order legacyBuy = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-buy-" + UUID.randomUUID(), "a".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacyBuy.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	@Test
	@DisplayName("샌드박스 지정가 매도 PENDING 주문은 현금 예약이 아니라 holdings.reservedQuantity를 쓰므로 백필 대상이 아니다")
	void backfillDoesNotTouchPendingSandboxSellOrders() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-sell@finplay.com", "hash", "v46legacysell", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-sell");
		Order legacySell = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.SELL, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-sell-" + UUID.randomUUID(), "b".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacySell.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + UUID.randomUUID().toString().substring(0, 8),
			scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrumentRepository.saveAndFlush(instrument);
	}
}
