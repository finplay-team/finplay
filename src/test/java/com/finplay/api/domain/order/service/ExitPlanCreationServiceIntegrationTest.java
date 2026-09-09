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
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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
			Account.create(user, Market.CRYPTO, LocalDateTime.now(clock)));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
