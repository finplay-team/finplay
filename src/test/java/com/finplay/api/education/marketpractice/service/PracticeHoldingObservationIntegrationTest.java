// 즐겨찾기 -> 의도 -> 시장가 매수(FILLED) -> holding 위에서 실습 3단계 관찰 API를 호출해 evidence A 판정과
// 저장을 실제 MySQL 트랜잭션으로 검증하는 통합 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
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

	private String priceKeyToCleanUp;

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		if (priceKeyToCleanUp != null) {
			redisTemplate.delete(priceKeyToCleanUp);
		}
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
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		String symbol = "OBS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceKeyToCleanUp = "price:crypto:" + symbol;
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW);

		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
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
