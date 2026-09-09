package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
class TutorialSandboxPracticeIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 12, 10, 0, 0);
	private static final BigDecimal SAMPLE_QUANTITY = new BigDecimal("1");
	private static final BigDecimal SAMPLE_SELL_QUANTITY = new BigDecimal("0.6");
	private static final BigDecimal SAMPLE_STOP_LOSS = new BigDecimal("8000");
	private static final BigDecimal SAMPLE_TAKE_PROFIT = new BigDecimal("12000");
	private static final BigDecimal CRYPTO_QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal CRYPTO_ENTRY_PRICE = new BigDecimal("100000");
	private static final BigDecimal CRYPTO_STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal CRYPTO_TAKE_PROFIT = new BigDecimal("120000");

	@Autowired
	private PracticeHoldingReflectionService practiceHoldingReflectionService;
	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@Autowired
	private InvestmentPracticeQueryService investmentPracticeQueryService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeIntentionService practiceIntentionService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private PriceQueryService priceQueryService;
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
	private PracticeProgressRepository practiceProgressRepository;
	@Autowired
	private PracticeCompletionRepository practiceCompletionRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;

	private final List<String> priceKeysToCleanUp = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceKeysToCleanUp.forEach(redisTemplate::delete);
	}

	@Test
	void sampleInstrumentChainCompletesAllFourStepsWithBuySellWithinFiveMinutes() {
		SampleChainFixture fixture = buildSampleCryptoChainUpToHolding("sandbox-happy");

		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		clock.set(BASE_NOW.plusSeconds(150));
		orderService.createOrder(fixture.userId(), "sandbox-happy-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET",
				SAMPLE_SELL_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(160));
		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "매도 후 복기 작성."));
		assertThat(reflection.holdingId()).isEqualTo(fixture.holdingId());

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("COMPLETED");
		assertThat(practiceCompletionRepository.findAll())
			.anySatisfy(completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));

		InvestmentPracticeResponse progress = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.CRYPTO);
		assertThat(progress.status()).isEqualTo("COMPLETED");
		assertThat(progress.steps()).hasSize(4);
		assertThat(progress.steps()).allSatisfy(
			step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		PracticeStepResponse stepFour = progress.steps().get(3);
		assertThat(stepFour.evidence().sellTradeId()).isNotNull();
		assertThat(stepFour.evidence().saleDeadlineAt()).isNotNull();
	}

	@Test
	void sampleInstrumentChainExpiresAndReflectionIsRejectedWhenNoSaleWithinFiveMinutes() {
		SampleChainFixture fixture = buildSampleCryptoChainUpToHolding("sandbox-expired");

		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		clock.set(BASE_NOW.plusSeconds(303));

		InvestmentPracticeResponse progressBeforeReflection = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.CRYPTO);
		assertThat(progressBeforeReflection.steps()).hasSize(4);
		assertThat(progressBeforeReflection.steps().get(3).status()).isEqualTo("EXPIRED");

		assertThatThrownBy(() -> practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "매도 없이 시도.")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("IN_PROGRESS");
		assertThat(practiceCompletionRepository.findAll())
			.noneSatisfy(completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));
	}

	@Test
	void sampleInstrumentChainCanRetryFromScratchAfterExpiryWithNewBuy() {
		SampleChainFixture fixture = buildSampleCryptoChainUpToHolding("sandbox-retry");

		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		clock.set(BASE_NOW.plusSeconds(303));
		assertThatThrownBy(() -> practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "만료 확인.")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		clock.set(BASE_NOW.plusSeconds(310));
		orderService.createOrder(fixture.userId(), "sandbox-retry-rebuy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", SAMPLE_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(340));
		orderService.createOrder(fixture.userId(), "sandbox-retry-resell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET",
				SAMPLE_SELL_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(350));
		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "재도전 후 복기."));
		assertThat(reflection.holdingId()).isEqualTo(fixture.holdingId());

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("COMPLETED");

		InvestmentPracticeResponse progressAfterRetry = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.CRYPTO);
		assertThat(progressAfterRetry.status()).isEqualTo("COMPLETED");
		assertThat(progressAfterRetry.steps()).hasSize(4);
		assertThat(progressAfterRetry.steps()).allSatisfy(
			step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	@Test
	void realInstrumentPriceQueryStillThrowsUnavailableWithoutFeedAndThreeStepFlowStillCompletes() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("real-regression"), "password-hash", uniqueNickname("real-regression"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));

		String symbol = "REG" + shortRandom();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "회귀검증코인", new BigDecimal("0.00000001"), 0L, true, BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);

		assertThatThrownBy(() -> priceQueryService.getPrice(instrument.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));

		priceStore.saveTick(symbol, CRYPTO_ENTRY_PRICE, BASE_NOW);
		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), CRYPTO_QUANTITY, CRYPTO_STOP_LOSS,
				CRYPTO_TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), "real-regression-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", CRYPTO_QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		clock.set(BASE_NOW.plusSeconds(12));
		priceStore.saveTick(symbol, CRYPTO_ENTRY_PRICE, BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(72));
		priceStore.saveTick(symbol, CRYPTO_ENTRY_PRICE, BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(132));
		priceStore.saveTick(symbol, CRYPTO_ENTRY_PRICE, BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));

		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			user.getId(), new PracticeHoldingReflectionCreateRequest(holding.getId(), "매도 없이 3단계에서 완료."));
		assertThat(reflection.holdingId()).isEqualTo(holding.getId());

		InvestmentPracticeResponse progress = investmentPracticeQueryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(progress.status()).isEqualTo("COMPLETED");
		assertThat(progress.steps()).hasSize(3);
		assertThat(progress.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	@Test
	void nonTradableSampleInstrumentsAreRejectedFromFavorites() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("sandbox-not-tradable"), "password-hash", uniqueNickname("sandbox-not-tradable"),
				BASE_NOW));

		Instrument secondStockSample = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_2")
			.orElseThrow();
		Instrument thirdStockSample = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_3")
			.orElseThrow();
		Instrument secondCoinSample = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_2")
			.orElseThrow();

		assertThat(secondStockSample.isTradable()).isFalse();
		assertThat(thirdStockSample.isTradable()).isFalse();
		assertThat(secondCoinSample.isTradable()).isFalse();

		assertThatThrownBy(() -> favoriteService.createFavorite(user.getId(), secondStockSample.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
		assertThatThrownBy(() -> favoriteService.createFavorite(user.getId(), thirdStockSample.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
		assertThatThrownBy(() -> favoriteService.createFavorite(user.getId(), secondCoinSample.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
	}

	private record SampleChainFixture(Long userId, Long holdingId, Long instrumentId) {
	}

	private SampleChainFixture buildSampleCryptoChainUpToHolding(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument sampleInstrument = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_1")
			.orElseThrow();
		assertThat(sampleInstrument.isTutorialSample()).isTrue();
		assertThat(sampleInstrument.isTradable()).isTrue();

		favoriteService.createFavorite(user.getId(), sampleInstrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(
				sampleInstrument.getId(), SAMPLE_QUANTITY, SAMPLE_STOP_LOSS, SAMPLE_TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), scenario + "-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, sampleInstrument.getId(), OrderSide.BUY, "MARKET", SAMPLE_QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), sampleInstrument.getId())
			.orElseThrow();
		return new SampleChainFixture(user.getId(), holding.getId(), sampleInstrument.getId());
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
