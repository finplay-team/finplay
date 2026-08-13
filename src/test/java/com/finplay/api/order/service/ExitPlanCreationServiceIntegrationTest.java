// ExitPlanCreationService.create()의 실제 트랜잭션(holding 잠금→예약→exit_plans·exit_plan_conditions 저장)을
// 실제 Spring 컨텍스트(Testcontainers MySQL)로 검증하는 통합 테스트다. 021은 아직 controller가 없어(#348에서
// 추가 예정) 서비스 메서드를 직접 호출한다 — ADR-0003 "핵심 시나리오 통합 테스트 1개".
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanCondition;
import com.finplay.api.order.domain.ExitPlanConditionType;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExitPlanCreationServiceIntegrationTest {

	@Autowired
	private ExitPlanCreationService exitPlanCreationService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private ExitPlanRepository exitPlanRepository;

	@Autowired
	private ExitPlanConditionRepository exitPlanConditionRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private Clock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	// 시나리오: 실제 holding을 보유한 계좌가 PRICE 방식 OCO 생성을 요청하면, 한 트랜잭션 안에서
	// holding.reservedQuantity가 늘고 exit_plans 1행·exit_plan_conditions 2행(손절·익절)이 실제로 커밋된다.
	@Test
	@Transactional
	void createPersistsReservationAndPlanConditionsInOneTransaction() {
		User user = createUser("exit-plan-e2e");
		Account account = createAccount(user);
		Instrument instrument = firstCryptoInstrument();
		priceStore.saveTick(instrument.getSymbol(), new BigDecimal("100500.00000000"), LocalDateTime.now(clock));

		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, LocalDateTime.now(clock)));
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), LocalDateTime.now(clock));
		holdingRepository.saveAndFlush(holding);

		BigDecimal quantity = new BigDecimal("1.00000000");
		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPrice(
			holding.getAveragePrice(), new BigDecimal("95000.00000000"), new BigDecimal("110000.00000000"));
		ExitPlanCreateCommandDto command = ExitPlanCreateCommandDto.general(
			user, holding, quantity, priceInput, "1".repeat(64));

		ExitPlan result = exitPlanCreationService.create(command);

		Holding reloadedHolding = holdingRepository.findById(holding.getId()).orElseThrow();
		assertThat(reloadedHolding.getReservedQuantity()).isEqualByComparingTo(quantity);

		ExitPlan persistedPlan = exitPlanRepository.findById(result.getId()).orElseThrow();
		assertThat(persistedPlan.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		assertThat(persistedPlan.getExitPriceType()).isEqualTo(ExitPriceType.PRICE);

		List<ExitPlanCondition> conditions = exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(result.getId());
		assertThat(conditions)
			.extracting(ExitPlanCondition::getConditionType)
			.containsExactly(ExitPlanConditionType.STOP_LOSS, ExitPlanConditionType.TAKE_PROFIT);
	}

	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, LocalDateTime.now(clock)));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
