// 즐겨찾기 -> 의도 -> 시장가 매수(FILLED) -> holding -> 관찰(evidence 충족) -> 복기 전체 흐름을 실제 MySQL
// 트랜잭션으로 검증하는 통합 테스트다. evidence 없이 복기를 시도하면 거부되고 아무것도 저장되지 않음도 확인한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
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

// PracticeHoldingObservationIntegrationTest와 동일 관례 — 이 클래스의 @Transactional은 JPA(MySQL) 쓰기만
// 테스트 종료 시 롤백한다. Redis(price:crypto:*)는 롤백 대상이 아니라 @AfterEach에서 별도로 지운다.
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeHoldingReflectionIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("120000");

	@Autowired
	private PracticeHoldingReflectionService practiceHoldingReflectionService;
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
	private PracticeProgressRepository practiceProgressRepository;
	@Autowired
	private PracticeCompletionRepository practiceCompletionRepository;
	@Autowired
	private PracticeMarketReflectionRepository practiceMarketReflectionRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;

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
	void reflectionCompletesTutorialAndPersistsReflectionAndCompletionWhenEvidenceExists() {
		ChainFixture fixture = buildFilledChainAndHolding("reflection-happy");

		// evidence A(경계 접근)를 만족시키는 관찰 1건을 먼저 쌓는다.
		priceStore.saveTick(fixture.symbol(), new BigDecimal("95000"), BASE_NOW.plusMinutes(1));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		PracticeHoldingReflectionResponse response = practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "손절 라인에 근접해 매도를 보류했다."));

		assertThat(response.holdingId()).isEqualTo(fixture.holdingId());
		assertThat(response.answer()).isEqualTo("손절 라인에 근접해 매도를 보류했다.");

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY).orElseThrow().getStatus())
			.isEqualTo(PracticeProgressStatus.COMPLETED);

		assertThat(practiceMarketReflectionRepository.findByUserIdAndTutorialKey(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY)).isPresent();
		assertThat(practiceCompletionRepository.findAll()).anySatisfy(
			completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));
	}

	@Test
	void reflectionIsRejectedAndNothingIsSavedWhenNoObservationEvidenceExists() {
		ChainFixture fixture = buildFilledChainAndHolding("reflection-no-evidence");
		// 관찰을 하나도 만들지 않는다 -> evidence 자체가 없다.

		assertThatThrownBy(() -> practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "관찰 없이 시도.")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY).orElseThrow().getStatus())
			.isEqualTo(PracticeProgressStatus.IN_PROGRESS);
		assertThat(practiceMarketReflectionRepository.findByUserIdAndTutorialKey(
			fixture.userId(), PracticeIntentionService.COIN_TUTORIAL_KEY)).isEmpty();
		assertThat(practiceCompletionRepository.findAll()).noneSatisfy(
			completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));
	}

	// 이슈 #343: 완료 트랜잭션이 실제로 커밋되면 계좌 cash_balance가 500만원 증가해야 한다(mock이 아닌 실제
	// MySQL 조회로 확인).
	@Test
	void reflectionIncreasesAccountCashBalanceByFiveMillionOnCompletion() {
		ChainFixture fixture = buildFilledChainAndHolding("reward-happy");

		priceStore.saveTick(fixture.symbol(), new BigDecimal("95000"), BASE_NOW.plusMinutes(1));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		long cashBalanceBeforeReflection = accountRepository
			.findByUserIdAndMarket(fixture.userId(), Market.CRYPTO)
			.orElseThrow()
			.getCashBalance();

		practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "보상 지급 확인용 복기."));

		long cashBalanceAfterReflection = accountRepository
			.findByUserIdAndMarket(fixture.userId(), Market.CRYPTO)
			.orElseThrow()
			.getCashBalance();

		assertThat(cashBalanceAfterReflection - cashBalanceBeforeReflection).isEqualTo(5_000_000L);
	}

	// 이슈 #343: 같은 사용자가 주식·코인 튜토리얼을 각각 완료하면 보상이 각 시장 계좌에 독립적으로(각 500만원)
	// 지급돼야 한다. 코인은 실제 종목 chain(3단계), 주식은 SANDBOX_STK_1 샘플 종목(4단계, 재생세션 없이도 거래
	// 가능 — TradeTest.allowsTutorialSampleStockTradeWithoutReplaySession)으로 완결한다.
	@Test
	void completingBothMarketTutorialsPaysRewardIndependentlyToEachAccount() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("dual-market"), "password-hash", uniqueNickname("dual-market"), BASE_NOW));
		Account cryptoAccount = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Account stockAccount = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, BASE_NOW));

		// 코인: 실제 종목 3단계 완료.
		String symbol = "DUAL" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		Instrument cryptoInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "dual-market코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW);

		favoriteService.createFavorite(user.getId(), cryptoInstrument.getId());
		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(cryptoInstrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));
		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), "dual-market-crypto-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, cryptoInstrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));
		Holding cryptoHolding = holdingRepository
			.findByAccountIdAndInstrumentId(cryptoAccount.getId(), cryptoInstrument.getId())
			.orElseThrow();

		clock.set(BASE_NOW.plusSeconds(12));
		// evidence A(경계 접근): 매수가(100000)보다 손절가(90000)에 더 가까운 95000으로 틱을 갱신한다
		// (buildFilledChainAndHolding 기반 기존 통과 테스트와 동일한 값).
		priceStore.saveTick(symbol, new BigDecimal("95000"), BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(cryptoHolding.getId()));

		practiceHoldingReflectionService.createReflection(
			user.getId(), new PracticeHoldingReflectionCreateRequest(cryptoHolding.getId(), "코인 튜토리얼 완료 복기."));

		long cryptoCashAfterCryptoCompletion = accountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow()
			.getCashBalance();
		long stockCashAfterCryptoCompletion = accountRepository
			.findByUserIdAndMarket(user.getId(), Market.STOCK)
			.orElseThrow()
			.getCashBalance();
		// 코인 완료 시점에는 코인 계좌만 보상을 받고 주식 계좌는 아직 초기 시드머니 그대로다.
		assertThat(stockCashAfterCryptoCompletion).isEqualTo(stockAccount.getCashBalance());

		// 주식: SANDBOX_STK_1 샘플 종목 4단계(매수 -> 5분 이내 매도 -> 복기) 완료.
		Instrument sampleStock = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		favoriteService.createFavorite(user.getId(), sampleStock.getId());
		clock.set(BASE_NOW.plusSeconds(20));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(sampleStock.getId(), new BigDecimal("1"), new BigDecimal("8000"),
				new BigDecimal("12000")));
		clock.set(BASE_NOW.plusSeconds(21));
		orderService.createOrder(user.getId(), "dual-market-stock-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, sampleStock.getId(), OrderSide.BUY, "MARKET", new BigDecimal("1")));
		Holding stockHolding = holdingRepository
			.findByAccountIdAndInstrumentId(stockAccount.getId(), sampleStock.getId())
			.orElseThrow();

		clock.set(BASE_NOW.plusSeconds(30));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(stockHolding.getId()));
		clock.set(BASE_NOW.plusSeconds(90));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(stockHolding.getId()));
		clock.set(BASE_NOW.plusSeconds(150));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(stockHolding.getId()));

		clock.set(BASE_NOW.plusSeconds(170));
		// 주식 수량은 정수여야 한다(OrderExecutionService.validateQuantityFormat) — 코인과 달리 소수 매도 불가.
		orderService.createOrder(user.getId(), "dual-market-stock-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, sampleStock.getId(), OrderSide.SELL, "MARKET",
				new BigDecimal("1")));

		// 매수·매도 체결 자체도 현금에 영향을 주므로(체결가·수수료 등), 보상 지급 효과만 분리해서 보려면
		// 복기(보상 지급) 바로 이전 시점의 잔고를 기준으로 삼아야 한다.
		long stockCashBeforeReward = accountRepository
			.findByUserIdAndMarket(user.getId(), Market.STOCK)
			.orElseThrow()
			.getCashBalance();

		clock.set(BASE_NOW.plusSeconds(180));
		practiceHoldingReflectionService.createReflection(
			user.getId(), new PracticeHoldingReflectionCreateRequest(stockHolding.getId(), "주식 튜토리얼 완료 복기."));

		long cryptoCashAfterBothCompletions = accountRepository
			.findByUserIdAndMarket(user.getId(), Market.CRYPTO)
			.orElseThrow()
			.getCashBalance();
		long stockCashAfterBothCompletions = accountRepository
			.findByUserIdAndMarket(user.getId(), Market.STOCK)
			.orElseThrow()
			.getCashBalance();

		// 코인 계좌는 주식 완료로 영향받지 않고, 주식 계좌는 보상 지급분(500만원)만큼만 늘어난다 — 시장별
		// 독립 지급. 매수·매도 체결 자체의 현금 영향은 stockCashBeforeReward 기준으로 제외한다.
		assertThat(cryptoCashAfterBothCompletions).isEqualTo(cryptoCashAfterCryptoCompletion);
		assertThat(stockCashAfterBothCompletions - stockCashBeforeReward).isEqualTo(5_000_000L);
	}

	private record ChainFixture(Long userId, Long holdingId, String symbol) {
	}

	/**
	 * favorite -> intention(절대 가격 손절·익절) -> 코인 시장가 매수 FILLED -> holding 순으로 chain을 완성한다.
	 * PracticeHoldingObservationIntegrationTest와 동일하게 코인 market을 쓴다(PriceStore만 필요해 준비가 단순함).
	 */
	private ChainFixture buildFilledChainAndHolding(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));

		String symbol = "REF" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW);

		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), "reflection-" + scenario + "-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		return new ChainFixture(user.getId(), holding.getId(), symbol);
	}

	// nickname 컬럼은 VARCHAR(50)이라 시나리오명 + UUID 전체(32자)를 이어붙이면 잘릴 수 있다 — 8자로 줄인다.
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
