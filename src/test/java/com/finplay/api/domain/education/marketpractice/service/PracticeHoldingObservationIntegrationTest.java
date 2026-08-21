// 즐겨찾기 -> 의도 -> 시장가 매수(FILLED) -> holding 위에서 실습 3단계 관찰 API를 호출해 evidence A 판정과
// 저장을 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
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
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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

// 이 클래스의 @Transactional은 JPA(MySQL) 쓰기만 테스트 종료 시 롤백한다 — User/Account/Instrument/
// Holding/Trade가 다른 테스트 클래스(예: InstrumentRepositoryTest의 시드 데이터 개수 단정)로 누출되지
// 않게 한다(AccountSummaryIntegrationTest와 동일 관례). Redis(price:crypto:*)는 이 롤백 대상이 아니라
// @AfterEach에서 별도로 지운다.
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

		// entryPrice와 동일 → baselineDistance == currentDistance(경계 접근 미충족), 관찰 1건뿐이라 B도 미충족.
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
		// PR #300 리뷰 회귀 테스트: MarketPracticeChainResolutionService.resolve()는 buyTradeExecutedAt이 가장
		// 이른 chain 하나만 우선순위로 고른다. 이 테스트는 같은 사용자가 코인 종목 두 개를 각각 독립적으로
		// 완결했을 때, "우선순위가 아닌" 두 번째 holding에 대한 관찰도 정상 처리되는지(409로 오탐하지 않는지)
		// 검증한다 — resolveForInstrument로 고치기 전에는 여기서 PRACTICE_EVIDENCE_MISSING이 났다.
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

		// priorityChain의 holding에 대한 관찰도 여전히 정상 동작해야 한다(한쪽만 고치다 다른 쪽을 깨지 않았는지).
		priceStore.saveTick(priorityChain.symbol(), ENTRY_PRICE, BASE_NOW.plusMinutes(1));
		PracticeHoldingObservationResponse priorityResponse = practiceHoldingObservationService.createObservation(
			priorityChain.userId(), new PracticeHoldingObservationCreateRequest(priorityChain.holdingId()));
		assertThat(priorityResponse.holdingId()).isEqualTo(priorityChain.holdingId());
	}

	// 030 holding 관찰 세션 역추적(이슈 #321) — buyTrade가 교육 지정가 세션에 귀속되면 PriceStore(실제 시세)가
	// 아니라 세션의 currentPrice를 관찰 가격으로 써야 한다. 이 종목의 PriceStore 틱을 의도적으로 세팅하지 않는다
	// — fallback 경로로 새면 PRICE_UNAVAILABLE(409)로 즉시 드러나고, 그렇지 않더라도 세션 tick 1 가격은
	// PracticePriceGeneratorV1로 독립 재계산한 값과 정확히 일치해야만 세션 경로가 실제로 쓰였다고 증명된다.
	@Test
	void observationUsesPracticeSessionCurrentPriceWhenBuyTradeIsSessionScoped() {
		BigDecimal quantity = BigDecimal.ONE;
		// startPrice(10,000)보다 항상 높아 tick 1에서 즉시 체결된다(±1%/tick, PracticeLimitOrderTickIntegrationTest와 동일 계약).
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

	// Account·Holding·Instrument는 LAZY 연관이라 저장 트랜잭션 바깥에서 재조회하면
	// LazyInitializationException 위험이 있다 — 필요한 값(userId·holdingId·symbol)만 트랜잭션 안에서 뽑아
	// 이 fixture로 넘긴다.
	private record ChainFixture(Long userId, Long holdingId, String symbol) {
	}

	/**
	 * favorite → intention(절대 가격 손절·익절) → 코인 시장가 매수 FILLED → holding 순으로 chain을 완성한다. 코인
	 * market을 쓰는 이유는 주식 경로(StockPriceProvider·재생세션)보다 준비가 단순한 {@link PriceStore}(Redis)만
	 * 필요하기 때문이다 — chain 해석·evidence 판정 로직 자체는 market과 무관하다.
	 */
	private ChainFixture buildFilledChainAndHolding(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		return buildFilledChainAndHolding(scenario, user, account, 0);
	}

	/**
	 * 이미 만들어진 user·account 위에 instrument chain을 완결한다 — 한 사용자가 같은 market에서 종목을 여러 개
	 * 완결하는 시나리오(PR #300 리뷰 회귀 테스트)를 준비하는 용도다. {@code baseOffsetSeconds}로 chain마다
	 * {@code BASE_NOW} 기준 시각을 겹치지 않게 벌려, 두 번째 chain의 intention·매수 시각이 첫 번째보다 항상
	 * 뒤가 되도록 한다(같은 clock을 여러 chain이 공유해도 서로 되돌리지 않는다).
	 */
	private ChainFixture buildFilledChainAndHolding(
		String scenario, User user, Account account, long baseOffsetSeconds) {
		String symbol = "OBS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceKeysToCleanUp.add("price:crypto:" + symbol);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		// PriceStore.isStale은 STALE_THRESHOLD(10초)를 주입된 Clock 기준으로 비교한다. 틱 시각을 이 chain의
		// clock 오프셋(baseOffsetSeconds)에 맞춰 두면 그 시점의 clock.now()보다 절대 앞서지 않아, 이후 관찰
		// 단계에서 clock을 더 전진시키지 않는 이 테스트 구조상 stale 계산이 항상 음수(미래 시각과 비교)가
		// 되어 10초 임계값을 넘지 않는다.
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
