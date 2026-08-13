// V35가 만든 exit_plan_idempotency_keys의 (user_id, idempotency_key) unique와 key-first 조회를 검증하는 슬라이스 테스트다 (021 plan "멱등성").
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanIdempotencyKey;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ExitPlanIdempotencyKeyRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 10, 0);
	private static final String REQUEST_HASH = "0".repeat(64);
	private static final String OTHER_REQUEST_HASH = "1".repeat(64);

	@Autowired
	private ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository;
	@Autowired
	private ExitPlanRepository exitPlanRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private EntityManager entityManager;

	private User user;
	private Account account;
	private ExitPlan plan;

	@BeforeEach
	void setUp() {
		user = createUser();
		account = createAccount(user);
		plan = createPlan(user, account);
	}

	@Test
	@DisplayName("key-first 조회는 본인 key만 찾고 저장한 plan·request hash를 그대로 돌려준다")
	void findByUserIdAndIdempotencyKeyReturnsMappingForOwnerOnly() {
		String key = UUID.randomUUID().toString();
		exitPlanIdempotencyKeyRepository.saveAndFlush(
			ExitPlanIdempotencyKey.of(user, key, REQUEST_HASH, plan, NOW));
		entityManager.clear();

		assertThat(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(user.getId(), key))
			.isPresent()
			.get()
			.satisfies(mapping -> {
				assertThat(mapping.getRequestHash()).isEqualTo(REQUEST_HASH);
				assertThat(mapping.getExitPlan().getId()).isEqualTo(plan.getId());
				assertThat(mapping.getCreatedAt()).isEqualTo(NOW);
			});
		assertThat(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(user.getId() + 1, key)).isEmpty();
		assertThat(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(
			user.getId(), UUID.randomUUID().toString())).isEmpty();
	}

	@Test
	@DisplayName("같은 사용자가 같은 Idempotency-Key를 두 번 저장하면 unique 제약에 걸린다 — 동시 재시도의 폴백 지점이다")
	void rejectsDuplicateIdempotencyKeyForSameUser() {
		String key = UUID.randomUUID().toString();
		exitPlanIdempotencyKeyRepository.saveAndFlush(
			ExitPlanIdempotencyKey.of(user, key, REQUEST_HASH, plan, NOW));

		ExitPlan secondPlan = createPlan(user, account);
		ExitPlanIdempotencyKey duplicate = ExitPlanIdempotencyKey.of(user, key, OTHER_REQUEST_HASH, secondPlan,
			NOW.plusSeconds(1));

		assertThatThrownBy(() -> exitPlanIdempotencyKeyRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("unique는 (user_id, idempotency_key) 복합이므로 다른 사용자는 같은 key를 쓸 수 있다")
	void allowsSameIdempotencyKeyForDifferentUsers() {
		String key = UUID.randomUUID().toString();
		exitPlanIdempotencyKeyRepository.saveAndFlush(
			ExitPlanIdempotencyKey.of(user, key, REQUEST_HASH, plan, NOW));

		User other = createUser();
		ExitPlan otherPlan = createPlan(other, createAccount(other));

		ExitPlanIdempotencyKey saved = exitPlanIdempotencyKeyRepository.saveAndFlush(
			ExitPlanIdempotencyKey.of(other, key, REQUEST_HASH, otherPlan, NOW));

		assertThat(saved.getId()).isNotNull();
		assertThat(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(other.getId(), key))
			.isPresent();
	}

	private User createUser() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create("epk-" + suffix + "@finplay.com", "hash", "epk-" + suffix, NOW));
	}

	// accounts에 UNIQUE(user_id, market)가 있어 사용자당 CRYPTO 계좌는 한 번만 만든다.
	private Account createAccount(User owner) {
		return accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
	}

	private ExitPlan createPlan(User owner, Account ownerAccount) {
		String symbol = "EPK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "테스트코인", new BigDecimal("0.00000001"), 0L, true, NOW));
		Holding holding = Holding.create(ownerAccount, instrument, NOW);
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		return exitPlanRepository.saveAndFlush(ExitPlan.createGeneral(
			owner,
			holding,
			instrument,
			new BigDecimal("1.00000000"),
			new BigDecimal("100000.00000000"),
			ExitPriceType.PRICE,
			null,
			null,
			new BigDecimal("95000.00000000"),
			new BigDecimal("110000.00000000"),
			new BigDecimal("100500.00000000"),
			NOW,
			REQUEST_HASH,
			NOW));
	}
}
