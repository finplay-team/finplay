package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.education.priceruntime.service.PracticeLimitOrderService;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceGeneratorV1;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceTickService;
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
class PracticeHoldingObservationIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("120000");

	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
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
	private PracticeMarketObservationRepository observationRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;
	@Autowired
	private PracticePriceSessionRepository practicePriceSessionRepository;
	@Autowired
	private PracticeLimitOrderService practiceLimitOrderService;
	@Autowired
	private PracticePriceTickService practicePriceTickService;

	private final List<String> priceKeysToCleanUp = new java.util.ArrayList<>();

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
	void observationSatisfiesEvidenceAWhenCurrentPriceIsCloserToBoundaryThanEntryPrice() {
		ChainFixture fixture = buildFilledChainAndHolding("evidence-a");

		priceStore.saveTick(fixture.symbol(), new BigDecimal("95000"), BASE_NOW.plusMinutes(1));

		PracticeHoldingObservationResponse response = practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		assertThat(response.holdingId()).isEqualTo(fixture.holdingId());
		assertThat(response.currentPrice()).isEqualByComparingTo("95000");
		assertThat(response.closerToBoundary()).isTrue();
		assertThat(response.closerBoundary()).isEqualTo("STOP_LOSS");
		assertThat(response.evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");

		List<PracticeMarketObservation> saved = observationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(fixture.userId(), fixture.holdingId());
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getCurrentPrice()).isEqualByComparingTo("95000");
		assertThat(saved.get(0).getEvidenceType().name()).isEqualTo("CLOSER_TO_BOUNDARY");
	}

	@Test
	void observationHasNoEvidenceWhenCurrentPriceIsNotCloserThanEntryPriceAndFewerThanThreeObservations() {
		ChainFixture fixture = buildFilledChainAndHolding("evidence-none");

		priceStore.saveTick(fixture.symbol(), ENTRY_PRICE, BASE_NOW.plusMinutes(1));

		PracticeHoldingObservationResponse response = practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		assertThat(response.closerToBoundary()).isFalse();
		assertThat(response.closerBoundary()).isNull();
		assertThat(response.evidenceType()).isNull();

		List<PracticeMarketObservation> saved = observationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(fixture.userId(), fixture.holdingId());
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getEvidenceType()).isNull();
		assertThat(saved.get(0).getCloserToBoundary()).isFalse();
	}

	@Test
	void observationSucceedsForNonPriorityHoldingWhenUserHasTwoCompletedChainsInSameMarket() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("two-chains"), "password-hash", uniqueNickname("two-chains"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));

		ChainFixture priorityChain = buildFilledChainAndHolding("two-chains-a", user, account, 0);
		ChainFixture nonPriorityChain = buildFilledChainAndHolding("two-chains-b", user, account, 10);

		priceStore.saveTick(nonPriorityChain.symbol(), new BigDecimal("95000"), BASE_NOW.plusMinutes(1));

		PracticeHoldingObservationResponse response = practiceHoldingObservationService.createObservation(
			nonPriorityChain.userId(), new PracticeHoldingObservationCreateRequest(nonPriorityChain.holdingId()));

		assertThat(response.holdingId()).isEqualTo(nonPriorityChain.holdingId());
		assertThat(response.closerToBoundary()).isTrue();
		assertThat(response.closerBoundary()).isEqualTo("STOP_LOSS");

		priceStore.saveTick(priorityChain.symbol(), ENTRY_PRICE, BASE_NOW.plusMinutes(1));
		PracticeHoldingObservationResponse priorityResponse = practiceHoldingObservationService.createObservation(
			priorityChain.userId(), new PracticeHoldingObservationCreateRequest(priorityChain.holdingId()));
		assertThat(priorityResponse.holdingId()).isEqualTo(priorityChain.holdingId());
	}

	@Test
	void observationUsesPracticeSessionCurrentPriceWhenBuyTradeIsSessionScoped() {
		BigDecimal quantity = BigDecimal.ONE;
		BigDecimal alwaysFillsLimitPrice = new BigDecimal("30000");
		BigDecimal startPrice = new BigDecimal("10000.00000000");
		long seed = 741852L;

		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("session-price"), "password-hash", uniqueNickname("session-price"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument btc = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "BTC").orElseThrow();

		favoriteService.createFavorite(user.getId(), btc.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(btc.getId(), quantity, STOP_LOSS, TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		PracticePriceSession session = practicePriceSessionRepository.saveAndFlush(PracticePriceSession.create(
			user.getId(), btc.getId(), seed, (short)PracticePriceGeneratorV1.VERSION, startPrice,
			BASE_NOW.plusSeconds(2)));
		practiceLimitOrderService.createOrder(user.getId(),
			new PracticeLimitOrderCreateRequest(session.getId(), btc.getId(), quantity, alwaysFillsLimitPrice));

		clock.set(BASE_NOW.plusSeconds(5));
		practicePriceTickService.advanceTick(user.getId(), session.getId(), 1);

		Holding holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), btc.getId()).orElseThrow();

		PracticeHoldingObservationResponse response = practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));

		BigDecimal expectedTickOnePrice = PracticePriceGeneratorV1.nextPrice(seed, 1, startPrice, startPrice);
		assertThat(response.currentPrice()).isEqualByComparingTo(expectedTickOnePrice);
	}

	private record ChainFixture(Long userId, Long holdingId, String symbol) {
	}

	private ChainFixture buildFilledChainAndHolding(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		return buildFilledChainAndHolding(scenario, user, account, 0);
	}

	private ChainFixture buildFilledChainAndHolding(
		String scenario, User user, Account account, long baseOffsetSeconds) {
		String symbol = "OBS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
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
		orderService.createOrder(user.getId(), "obs-" + scenario + "-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		return new ChainFixture(user.getId(), holding.getId(), symbol);
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}
}
