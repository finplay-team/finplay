// 즐겨찾기 -> 의도 -> 시장가 매수(FILLED) -> holding -> 관찰(evidence 충족) -> 복기 전체 흐름을 실제 MySQL
// 트랜잭션으로 검증하는 통합 테스트다. evidence 없이 복기를 시도하면 거부되고 아무것도 저장되지 않음도 확인한다.
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
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
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
		assertThat(response.prompt()).isEqualTo(PracticeHoldingReflectionResponse.PROMPT);

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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));

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
