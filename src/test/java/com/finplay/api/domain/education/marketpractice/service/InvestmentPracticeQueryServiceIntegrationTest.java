package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class InvestmentPracticeQueryServiceIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("120000");

	@Autowired
	private InvestmentPracticeQueryService investmentPracticeQueryService;
	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@Autowired
	private PracticeHoldingReflectionService practiceHoldingReflectionService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeIntentionService practiceIntentionService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private TestClock clock;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;

	private final List<String> priceKeysToCleanUp = new ArrayList<>();

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceKeysToCleanUp.forEach(redisTemplate::delete);
	}

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void progressesThroughAllFiveStatesAndStaysCompletedAfterFavoriteIsDeleted() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("progress"), "password-hash", uniqueNickname("progress"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		String symbol = "PRG" + shortRandom();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "진행테스트코인", new BigDecimal("0.00000001"), 0L, true, BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW);

		InvestmentPracticeResponse notStarted = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(notStarted.status()).isEqualTo("NOT_STARTED");
		assertThat(notStarted.currentStep()).isEqualTo(1);
		assertThat(notStarted.completedAt()).isNull();
		assertThat(notStarted.steps().get(0).status()).isEqualTo("NOT_STARTED");
		assertThat(notStarted.steps().get(0).locked()).isFalse();
		assertThat(notStarted.steps().get(1).locked()).isTrue();
		assertThat(notStarted.steps().get(2).locked()).isTrue();

		favoriteService.createFavorite(user.getId(), instrument.getId());
		InvestmentPracticeResponse favoriteOnly = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(favoriteOnly.status()).isEqualTo("IN_PROGRESS");
		assertThat(favoriteOnly.currentStep()).isEqualTo(2);
		PracticeStepResponse favoriteStep1 = favoriteOnly.steps().get(0);
		assertThat(favoriteStep1.status()).isEqualTo("COMPLETED");
		assertThat(favoriteStep1.evidence().favoriteId()).isNotNull();
		PracticeStepResponse favoriteStep2 = favoriteOnly.steps().get(1);
		assertThat(favoriteStep2.status()).isEqualTo("IN_PROGRESS");
		assertThat(favoriteStep2.evidence().intentionId()).isNull();
		assertThat(favoriteOnly.steps().get(2).status()).isEqualTo("NOT_STARTED");
		assertThat(favoriteOnly.steps().get(2).locked()).isTrue();

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));
		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), "progress-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		InvestmentPracticeResponse chainNoObservation = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(chainNoObservation.status()).isEqualTo("IN_PROGRESS");
		assertThat(chainNoObservation.currentStep()).isEqualTo(3);
		assertThat(chainNoObservation.steps().get(0).status()).isEqualTo("COMPLETED");
		PracticeStepResponse step2NoObs = chainNoObservation.steps().get(1);
		assertThat(step2NoObs.status()).isEqualTo("COMPLETED");
		assertThat(step2NoObs.evidence().intentionId()).isNotNull();
		assertThat(step2NoObs.evidence().buyTradeId()).isNotNull();
		assertThat(step2NoObs.evidence().holdingId()).isEqualTo(holding.getId());
		assertThat(step2NoObs.evidence().referenceStopLossPrice()).isEqualByComparingTo(STOP_LOSS);
		assertThat(step2NoObs.evidence().referenceTakeProfitPrice()).isEqualByComparingTo(TAKE_PROFIT);
		PracticeStepResponse step3NoObs = chainNoObservation.steps().get(2);
		assertThat(step3NoObs.status()).isEqualTo("IN_PROGRESS");
		assertThat(step3NoObs.locked()).isFalse();
		assertThat(step3NoObs.evidence().holdingId()).isEqualTo(holding.getId());
		assertThat(step3NoObs.evidence().observationId()).isNull();
		assertThat(step3NoObs.evidence().evidenceType()).isNull();

		priceStore.saveTick(symbol, new BigDecimal("95000"), BASE_NOW.plusMinutes(1));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));

		InvestmentPracticeResponse chainWithObservation = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(chainWithObservation.status()).isEqualTo("IN_PROGRESS");
		assertThat(chainWithObservation.currentStep()).isEqualTo(3);
		PracticeStepResponse step3WithObs = chainWithObservation.steps().get(2);
		assertThat(step3WithObs.status()).isEqualTo("IN_PROGRESS");
		assertThat(step3WithObs.evidence().observationId()).isNotNull();
		assertThat(step3WithObs.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
		assertThat(step3WithObs.evidence().reflectionId()).isNull();

		practiceHoldingReflectionService.createReflection(user.getId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "손절 라인 근처였지만 계획대로 유지했다."));

		InvestmentPracticeResponse completed = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.currentStep()).isNull();
		assertThat(completed.completedAt()).isNotNull();
		for (PracticeStepResponse step : completed.steps()) {
			assertThat(step.status()).isEqualTo("COMPLETED");
			assertThat(step.locked()).isFalse();
			assertThat(step.evidence().favoriteId()).isNotNull();
			assertThat(step.evidence().intentionId()).isNotNull();
			assertThat(step.evidence().buyTradeId()).isNotNull();
			assertThat(step.evidence().holdingId()).isEqualTo(holding.getId());
			assertThat(step.evidence().observationId()).isNotNull();
			assertThat(step.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
			assertThat(step.evidence().reflectionId()).isNotNull();
		}

		favoriteService.deleteFavorite(user.getId(), instrument.getId());

		InvestmentPracticeResponse completedAfterFavoriteDeleted = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);
		assertThat(completedAfterFavoriteDeleted.status()).isEqualTo("COMPLETED");
		assertThat(completedAfterFavoriteDeleted.completedAt()).isEqualTo(completed.completedAt());
		PracticeStepResponse stepAfterDelete = completedAfterFavoriteDeleted.steps().get(0);
		assertThat(stepAfterDelete.evidence().favoriteId()).isNull();
		assertThat(stepAfterDelete.evidence().intentionId()).isNull();
		assertThat(stepAfterDelete.evidence().buyTradeId()).isNull();
		assertThat(stepAfterDelete.evidence().holdingId()).isEqualTo(holding.getId());
		assertThat(stepAfterDelete.evidence().observationId()).isNotNull();
		assertThat(stepAfterDelete.evidence().reflectionId()).isNotNull();
	}

	@Test
	void getProgressReflectsChainResolutionQualifyingObservationPriorityAmongTwoCompletedChains() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("priority"), "password-hash", uniqueNickname("priority"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));

		ChainFixture chainA = buildFilledChainAndHolding(user, account, "prio-a", 0);
		ChainFixture chainB = buildFilledChainAndHolding(user, account, "prio-b", 10);

		priceStore.saveTick(chainB.symbol(), new BigDecimal("95000"), BASE_NOW.plusSeconds(20));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(chainB.holdingId()));

		InvestmentPracticeResponse response = investmentPracticeQueryService.getProgress(
			user.getId(), Market.CRYPTO);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);
		PracticeStepResponse step2 = response.steps().get(1);
		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step2.evidence().holdingId()).isEqualTo(chainB.holdingId());
		assertThat(step2.evidence().holdingId()).isNotEqualTo(chainA.holdingId());
		assertThat(step3.evidence().observationId()).isNotNull();
		assertThat(step3.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
	}

	private record ChainFixture(Long holdingId, String symbol) {
	}

	private ChainFixture buildFilledChainAndHolding(
		User user, Account account, String scenario, long baseOffsetSeconds) {
		String symbol = "PRI" + shortRandom();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW.plusSeconds(baseOffsetSeconds));

		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(baseOffsetSeconds + 1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(baseOffsetSeconds + 2));
		orderService.createOrder(user.getId(), "priority-" + scenario + "-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		return new ChainFixture(holding.getId(), symbol);
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
