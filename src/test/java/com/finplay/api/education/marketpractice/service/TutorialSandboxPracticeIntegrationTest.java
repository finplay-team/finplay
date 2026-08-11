// 031-tutorial-sandbox-instruments: 샘플 종목 4단계 전체 흐름 + 5분 만료 + 실제 종목 회귀를 실제 시세 피드가
// 전혀 없는 상태(빗썸 poller 미기동, 주식 재생세션 미시딩)에서 검증하는 통합 테스트다. 이슈 #339의 직접
// 회귀 테스트(샘플 종목은 실제 시세 인프라 없이도 항상 진행 가능)이기도 하다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

// 이 클래스의 @Transactional은 JPA(MySQL) 쓰기만 테스트 종료 시 롤백한다(PracticeHoldingReflectionIntegrationTest와
// 동일 관례) — favorite·intention(ADR-0012 in-memory)은 롤백 대상이 아니지만 매 테스트가 새 userId를 쓰므로
// 서로 오염되지 않는다. Redis(price:crypto:*)도 롤백 대상이 아니라 @AfterEach에서 별도로 지운다.
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class TutorialSandboxPracticeIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 12, 10, 0, 0);
	private static final BigDecimal STOCK_QUANTITY = new BigDecimal("10");
	private static final BigDecimal STOCK_SELL_QUANTITY = new BigDecimal("5");
	private static final BigDecimal STOCK_STOP_LOSS = new BigDecimal("40000");
	private static final BigDecimal STOCK_TAKE_PROFIT = new BigDecimal("60000");
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

	// 시나리오 1: 샘플 종목(SANDBOX_STK_1, tradable=true) 즐겨찾기 -> 의도 -> 시장가 매수(장 시간·시세 무관하게
	// 성공, SANDBOX-002·003) -> evidence B(시간 분산 관찰 3회) -> 5분 이내 시장가 매도 -> 복기 -> GET에서
	// steps 4개 전부 COMPLETED. 빗썸 poller·주식 재생세션이 전혀 없는 상태에서 진행한다(이슈 #339 회귀).
	@Test
	void sampleInstrumentChainCompletesAllFourStepsWithBuySellWithinFiveMinutes() {
		StockChainFixture fixture = buildSampleStockChainUpToHolding("sandbox-happy");

		// evidence B: 관찰 3건 + 최초~최후 간격 2분 이상.
		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		// 매수(buyTrade.executedAt = BASE_NOW+2s) 후 5분 이내(+150s)에 시장가 매도(부분 매도, 전량 아님).
		clock.set(BASE_NOW.plusSeconds(150));
		orderService.createOrder(fixture.userId(), "sandbox-happy-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, fixture.instrumentId(), OrderSide.SELL, "MARKET", STOCK_SELL_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(160));
		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "매도 후 복기 작성."));
		assertThat(reflection.holdingId()).isEqualTo(fixture.holdingId());

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("COMPLETED");
		assertThat(practiceCompletionRepository.findAll())
			.anySatisfy(completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));

		InvestmentPracticeResponse progress = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.STOCK);
		assertThat(progress.status()).isEqualTo("COMPLETED");
		assertThat(progress.steps()).hasSize(4);
		assertThat(progress.steps()).allSatisfy(
			step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		PracticeStepResponse stepFour = progress.steps().get(3);
		assertThat(stepFour.evidence().sellTradeId()).isNotNull();
		assertThat(stepFour.evidence().saleDeadlineAt()).isNotNull();
	}

	// 시나리오 2: 샘플 종목 매수 후 매도 없이 5분을 넘기면 holding-reflections가 409
	// PRACTICE_SANDBOX_TIME_EXPIRED를 반환하고, GET 응답의 4번째 step은 EXPIRED다(SANDBOX-007·008).
	@Test
	void sampleInstrumentChainExpiresAndReflectionIsRejectedWhenNoSaleWithinFiveMinutes() {
		StockChainFixture fixture = buildSampleStockChainUpToHolding("sandbox-expired");

		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		// buyTrade.executedAt(BASE_NOW+2s) + 5분(300s) = BASE_NOW+302s. 매도 없이 1초 초과한 시점으로 이동.
		clock.set(BASE_NOW.plusSeconds(303));

		InvestmentPracticeResponse progressBeforeReflection = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.STOCK);
		assertThat(progressBeforeReflection.steps()).hasSize(4);
		assertThat(progressBeforeReflection.steps().get(3).status()).isEqualTo("EXPIRED");

		assertThatThrownBy(() -> practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "매도 없이 시도.")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("IN_PROGRESS");
		assertThat(practiceCompletionRepository.findAll())
			.noneSatisfy(completion -> assertThat(completion.getUserId()).isEqualTo(fixture.userId()));
	}

	// 시나리오 3(SANDBOX-007 "만료된 chain은 재시도 가능하다"): 만료된 chain에서 같은 종목·같은 intention으로
	// 다시 매수(새 buyTrade)한 뒤 그 매수로부터 5분 이내에 매도하면 처음부터 4단계를 다시 완료할 수 있다.
	// (구현 노트: 이 테스트를 작성하는 과정에서 MarketPracticeChainResolutionService.resolveForFavorite가
	// 원래 instrumentId당 "가장 이른" 매수 체결만 고정 선택해 재도전이 막히는 회귀를 발견했고, 샘플 종목
	// chain에서만 가장 최신 매수를 anchor로 쓰도록 수정했다 — TradeService.findLatestFilledBuyTradeMatching,
	// 실제 종목 chain의 026 anti-gaming 규칙(가장 이른 체결 고정)은 그대로 유지된다.)
	@Test
	void sampleInstrumentChainCanRetryFromScratchAfterExpiryWithNewBuy() {
		StockChainFixture fixture = buildSampleStockChainUpToHolding("sandbox-retry");

		clock.set(BASE_NOW.plusSeconds(12));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(72));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));
		clock.set(BASE_NOW.plusSeconds(132));
		practiceHoldingObservationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(fixture.holdingId()));

		// 매도 없이 5분 초과 -> 최초 chain 만료 확정.
		clock.set(BASE_NOW.plusSeconds(303));
		assertThatThrownBy(() -> practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "만료 확인.")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		// 같은 종목·같은(기존) intention으로 재매수(새 buyTrade, anchor 갱신) -> 새 anchor로부터 5분 이내에 매도.
		clock.set(BASE_NOW.plusSeconds(310));
		orderService.createOrder(fixture.userId(), "sandbox-retry-rebuy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, fixture.instrumentId(), OrderSide.BUY, "MARKET", STOCK_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(340));
		orderService.createOrder(fixture.userId(), "sandbox-retry-resell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, fixture.instrumentId(), OrderSide.SELL, "MARKET", STOCK_SELL_QUANTITY));

		clock.set(BASE_NOW.plusSeconds(350));
		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			fixture.userId(), new PracticeHoldingReflectionCreateRequest(fixture.holdingId(), "재도전 후 복기."));
		assertThat(reflection.holdingId()).isEqualTo(fixture.holdingId());

		assertThat(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			fixture.userId(), PracticeIntentionService.TUTORIAL_KEY).orElseThrow().getStatus().name())
			.isEqualTo("COMPLETED");

		InvestmentPracticeResponse progressAfterRetry = investmentPracticeQueryService.getProgress(
			fixture.userId(), Market.STOCK);
		assertThat(progressAfterRetry.status()).isEqualTo("COMPLETED");
		assertThat(progressAfterRetry.steps()).hasSize(4);
		assertThat(progressAfterRetry.steps()).allSatisfy(
			step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	// 시나리오 4: 실제 종목(is_tutorial_sample=false)의 회귀 확인. (a) PriceStore에 틱이 없으면 여전히
	// PRICE_UNAVAILABLE을 던진다(SANDBOX-002가 실제 종목에는 적용되지 않음). (b) 026의 기존 3단계 완료
	// 흐름(evidence B + 자유 복기)이 이 spec 이후에도 완전히 동일하게 성공하고 steps.length==3을 유지한다.
	@Test
	void realInstrumentPriceQueryStillThrowsUnavailableWithoutFeedAndThreeStepFlowStillCompletes() {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail("real-regression"), "password-hash", uniqueNickname("real-regression"), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));

		String symbol = "REG" + shortRandom();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "회귀검증코인", new BigDecimal("0.00000001"), 0L, true, BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);

		// (a) 실제 종목은 샘플 종목과 달리 시세 틱이 없으면 여전히 PRICE_UNAVAILABLE이어야 한다(회귀 없음).
		assertThatThrownBy(() -> priceQueryService.getPrice(instrument.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRICE_UNAVAILABLE));

		// (b) 026의 3단계 완료 흐름은 이 spec 이후에도 그대로 성공해야 한다.
		priceStore.saveTick(symbol, CRYPTO_ENTRY_PRICE, BASE_NOW);
		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), CRYPTO_QUANTITY, CRYPTO_STOP_LOSS, CRYPTO_TAKE_PROFIT));

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

		// 실제 종목 chain은 매도 없이도(4단계 자체가 없으므로) 3단계에서 곧바로 복기로 완료된다.
		PracticeHoldingReflectionResponse reflection = practiceHoldingReflectionService.createReflection(
			user.getId(), new PracticeHoldingReflectionCreateRequest(holding.getId(), "매도 없이 3단계에서 완료."));
		assertThat(reflection.holdingId()).isEqualTo(holding.getId());

		InvestmentPracticeResponse progress = investmentPracticeQueryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(progress.status()).isEqualTo("COMPLETED");
		assertThat(progress.steps()).hasSize(3);
		assertThat(progress.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	// 시나리오 5: 시장별 2·3번째 샘플 종목(tradable=false)은 목록에는 노출되지만 즐겨찾기 대상에서는 026이
	// 이미 상속한 "존재하지 않거나 비활성 종목" 배제 규칙(INSTRUMENT_NOT_TRADABLE)으로 그대로 거부된다.
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

	private record StockChainFixture(Long userId, Long holdingId, Long instrumentId) {
	}

	/**
	 * favorite -> intention -> 샘플 종목(SANDBOX_STK_1) 시장가 매수 FILLED -> holding까지 완결한다. 주식
	 * 재생세션·PriceStore 틱을 전혀 준비하지 않는다 — 샘플 종목은 PriceQueryService 분기로 이 인프라를
	 * 완전히 우회하기 때문이다(이슈 #339의 직접 회귀 지점).
	 */
	private StockChainFixture buildSampleStockChainUpToHolding(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, BASE_NOW));
		Instrument sampleInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sampleInstrument.isTutorialSample()).isTrue();
		assertThat(sampleInstrument.isTradable()).isTrue();

		favoriteService.createFavorite(user.getId(), sampleInstrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(
				sampleInstrument.getId(), STOCK_QUANTITY, STOCK_STOP_LOSS, STOCK_TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), scenario + "-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.STOCK, sampleInstrument.getId(), OrderSide.BUY, "MARKET", STOCK_QUANTITY));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), sampleInstrument.getId())
			.orElseThrow();
		return new StockChainFixture(user.getId(), holding.getId(), sampleInstrument.getId());
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
