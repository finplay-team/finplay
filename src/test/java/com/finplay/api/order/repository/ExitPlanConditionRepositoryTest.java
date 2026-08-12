// V34가 만든 exit_plan_conditions의 (exit_plan_id, condition_type) unique와 조회 메서드를 검증하는 슬라이스 테스트다 (021 plan "데이터 모델").
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
import com.finplay.api.order.domain.ExitPlanCondition;
import com.finplay.api.order.domain.ExitPlanConditionStatus;
import com.finplay.api.order.domain.ExitPlanConditionType;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ExitPlanConditionRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 10, 0);
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("95000.00000000");
	private static final BigDecimal TAKE_PROFIT_PRICE = new BigDecimal("110000.00000000");

	@Autowired
	private ExitPlanConditionRepository exitPlanConditionRepository;
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
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private User user;
	private Account account;
	private ExitPlan plan;

	@BeforeEach
	void setUp() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		user = userRepository.saveAndFlush(User.create("epc-" + suffix + "@finplay.com", "hash", "epc-" + suffix, NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		plan = createPlan();
	}

	@Test
	@DisplayName("한 plan은 손절·익절 조건 두 건을 갖고 id 오름차순으로 조회된다")
	void savesStopLossAndTakeProfitConditionsForOnePlan() {
		ExitPlanCondition stopLoss = exitPlanConditionRepository.saveAndFlush(
			ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, STOP_LOSS_PRICE, NOW));
		ExitPlanCondition takeProfit = exitPlanConditionRepository.saveAndFlush(
			ExitPlanCondition.create(plan, ExitPlanConditionType.TAKE_PROFIT, TAKE_PROFIT_PRICE, NOW));
		entityManager.clear();

		List<ExitPlanCondition> conditions = exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(plan.getId());

		assertThat(conditions)
			.extracting(ExitPlanCondition::getId)
			.containsExactly(stopLoss.getId(), takeProfit.getId());
		assertThat(conditions)
			.extracting(ExitPlanCondition::getConditionType)
			.containsExactly(ExitPlanConditionType.STOP_LOSS, ExitPlanConditionType.TAKE_PROFIT);
		assertThat(conditions)
			.allSatisfy(condition -> {
				assertThat(condition.getStatus()).isEqualTo(ExitPlanConditionStatus.PENDING);
				assertThat(condition.getCreatedAt()).isEqualTo(NOW);
				assertThat(condition.getExitPlan().getId()).isEqualTo(plan.getId());
			});
		assertThat(conditions.get(0).getTriggerPrice()).isEqualByComparingTo(STOP_LOSS_PRICE);
		assertThat(conditions.get(1).getTriggerPrice()).isEqualByComparingTo(TAKE_PROFIT_PRICE);
	}

	@Test
	@DisplayName("같은 plan에 같은 조건 유형을 두 번 저장하면 unique 제약에 걸린다 — plan당 손절·익절 각 1건이다")
	void rejectsDuplicateConditionTypeWithinSamePlan() {
		exitPlanConditionRepository.saveAndFlush(
			ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, STOP_LOSS_PRICE, NOW));

		ExitPlanCondition duplicate = ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS,
			new BigDecimal("94000.00000000"), NOW);

		assertThatThrownBy(() -> exitPlanConditionRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("unique는 plan 범위이므로 다른 plan은 같은 조건 유형을 가질 수 있다")
	void allowsSameConditionTypeAcrossDifferentPlans() {
		exitPlanConditionRepository.saveAndFlush(
			ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, STOP_LOSS_PRICE, NOW));
		ExitPlan otherPlan = createPlan();

		ExitPlanCondition saved = exitPlanConditionRepository.saveAndFlush(
			ExitPlanCondition.create(otherPlan, ExitPlanConditionType.STOP_LOSS, STOP_LOSS_PRICE, NOW));

		assertThat(saved.getId()).isNotNull();
		assertThat(exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(otherPlan.getId())).hasSize(1);
	}

	@Test
	@DisplayName("created_at이 없는 조건 행은 DB가 거부한다 — 컬럼이 NOT NULL이다")
	void rejectsConditionWithoutCreatedAt() {
		entityManager.flush();

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO exit_plan_conditions (exit_plan_id, condition_type, trigger_price, status, created_at) "
				+ "VALUES (?, 'STOP_LOSS', 95000.00000000, 'PENDING', NULL)",
			plan.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private ExitPlan createPlan() {
		String symbol = "EPC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "테스트코인", new BigDecimal("0.00000001"), 0L, true, NOW));
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		holdingRepository.saveAndFlush(holding);

		return exitPlanRepository.saveAndFlush(ExitPlan.createGeneral(
			user,
			holding,
			instrument,
			new BigDecimal("1.00000000"),
			new BigDecimal("100000.00000000"),
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			new BigDecimal("100500.00000000"),
			NOW,
			UUID.randomUUID().toString().replace("-", "").repeat(2),
			NOW));
	}
}
