// 코인 지정가 체결부터 튜토리얼 완료까지와 Spring Context 재생성 전후 상태를 실제 MySQL로 검증한다.
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
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CryptoLimitPracticeFlowRestartIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2033, 3, 13, 10, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("100000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("120000");

	private Long incompleteUserId;
	private Long completedUserId;
	private Long completedHoldingId;
	private LocalDateTime completedAt;
	private String incompleteSymbol;
	private String completedSymbol;
	private final List<Long> createdUserIds = new ArrayList<>();
	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdInstrumentIds = new ArrayList<>();

	@Autowired
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeIntentionService intentionService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void clearRedisPrices() {
		if (incompleteSymbol != null) {
			redisTemplate.delete("price:crypto:" + incompleteSymbol);
		}
		if (completedSymbol != null) {
			redisTemplate.delete("price:crypto:" + completedSymbol);
		}
		redisTemplate.delete("feed:crypto:status");
	}

	@AfterAll
	void cleanUpAllFixtures() {
		clearRedisPrices();
		cleanUpCommittedFixtures();
		createdUserIds.clear();
		createdAccountIds.clear();
		createdInstrumentIds.clear();
		incompleteUserId = null;
		completedUserId = null;
		completedHoldingId = null;
		completedAt = null;
		incompleteSymbol = null;
		completedSymbol = null;
	}

	@Test
	@Order(1)
	void incompleteEvidenceExistsBeforeSpringContextIsRecreated() {
		FlowFixture fixture = createFilledLimitBuyChain("restart-incomplete");
		incompleteUserId = fixture.userId();
		incompleteSymbol = fixture.symbol();

		InvestmentPracticeResponse progress = queryService.getProgress(incompleteUserId, Market.CRYPTO);

		assertThat(progress.status()).isEqualTo("IN_PROGRESS");
		assertThat(progress.currentStep()).isEqualTo(3);
		assertThat(progress.steps().get(1).evidence().holdingId()).isEqualTo(fixture.holdingId());
	}

	@Test
	@Order(2)
	void incompleteEvidenceIsLostThenCryptoLimitFlowCompletesAfterSpringContextIsRecreated() {
		InvestmentPracticeResponse regressed = queryService.getProgress(incompleteUserId, Market.CRYPTO);
		assertThat(regressed.status()).isEqualTo("NOT_STARTED");
		assertThat(regressed.currentStep()).isEqualTo(1);

		FlowFixture fixture = createFilledLimitBuyChain("restart-completed");
		completedUserId = fixture.userId();
		completedHoldingId = fixture.holdingId();
		completedSymbol = fixture.symbol();

		clock.set(BASE_NOW.plusSeconds(4));
		priceStore.saveTick(completedSymbol, new BigDecimal("95000"), BASE_NOW.plusSeconds(4));
		observationService.createObservation(
			completedUserId, new PracticeHoldingObservationCreateRequest(completedHoldingId));
		reflectionService.createReflection(completedUserId,
			new PracticeHoldingReflectionCreateRequest(completedHoldingId, "손절선에 가까워져도 계획을 지켰다."));

		InvestmentPracticeResponse completed = queryService.getProgress(completedUserId, Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.currentStep()).isNull();
		assertThat(completed.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		assertThat(completed.steps().get(2).evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
		completedAt = completed.completedAt();
	}

	@Test
	@Order(3)
	void databaseCompletionStaysCompletedAfterSpringContextIsRecreated() {
		InvestmentPracticeResponse completed = queryService.getProgress(completedUserId, Market.CRYPTO);

		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.currentStep()).isNull();
		assertThat(completed.completedAt()).isEqualTo(completedAt);
		assertThat(completed.steps()).allSatisfy(step -> {
			assertThat(step.status()).isEqualTo("COMPLETED");
			assertThat(step.evidence().favoriteId()).isNull();
			assertThat(step.evidence().intentionId()).isNull();
			assertThat(step.evidence().holdingId()).isEqualTo(completedHoldingId);
			assertThat(step.evidence().observationId()).isNotNull();
			assertThat(step.evidence().reflectionId()).isNotNull();
		});
	}

	private FlowFixture createFilledLimitBuyChain(String scenario) {
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + shortRandom() + "@finplay.com", "password-hash",
			scenario + "-" + shortRandom(), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		String symbol = "R" + shortRandom().toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, symbol, scenario, new BigDecimal("0.00000001"), 0L, true, BASE_NOW));
		createdUserIds.add(user.getId());
		createdAccountIds.add(account.getId());
		createdInstrumentIds.add(instrument.getId());

		InvestmentPracticeResponse notStarted = queryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(notStarted.status()).isEqualTo("NOT_STARTED");
		assertThat(notStarted.currentStep()).isEqualTo(1);

		favoriteService.createFavorite(user.getId(), instrument.getId());
		InvestmentPracticeResponse favoriteOnly = queryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(favoriteOnly.status()).isEqualTo("IN_PROGRESS");
		assertThat(favoriteOnly.currentStep()).isEqualTo(2);
		assertThat(favoriteOnly.steps().get(0).status()).isEqualTo("COMPLETED");
		assertThat(favoriteOnly.steps().get(1).status()).isEqualTo("IN_PROGRESS");
		clock.set(BASE_NOW.plusSeconds(1));
		intentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));
		clock.set(BASE_NOW.plusSeconds(2));
		var pending = limitOrderService.createLimitOrder(user.getId(), UUID.randomUUID().toString(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));
		assertThat(pending.status()).isEqualTo("PENDING");

		clock.set(BASE_NOW.plusSeconds(3));
		priceStore.saveTick(symbol, LIMIT_PRICE, BASE_NOW.plusSeconds(3));
		assertThat(orderRepository.findById(pending.orderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.FILLED);
		Holding holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holding.getQuantity()).isEqualByComparingTo(QUANTITY);
		InvestmentPracticeResponse filled = queryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(filled.status()).isEqualTo("IN_PROGRESS");
		assertThat(filled.currentStep()).isEqualTo(3);
		assertThat(filled.steps().get(1).status()).isEqualTo("COMPLETED");
		assertThat(filled.steps().get(2).status()).isEqualTo("IN_PROGRESS");
		return new FlowFixture(user.getId(), holding.getId(), symbol);
	}

	private void cleanUpCommittedFixtures() {
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("DELETE FROM practice_completions WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_market_reflections WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_market_observations WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", userId);
		}
		for (Long accountId : createdAccountIds) {
			jdbcTemplate.update(
				"DELETE FROM trade_allocations WHERE holding_lot_id IN "
					+ "(SELECT id FROM holding_lots WHERE holding_id IN "
					+ "(SELECT id FROM holdings WHERE account_id = ?))",
				accountId);
			jdbcTemplate.update(
				"DELETE FROM holding_lots WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
				accountId);
			jdbcTemplate.update("DELETE FROM trades WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM orders WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
		}
		createdInstrumentIds.forEach(
			instrumentId -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrumentId));
		createdUserIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
	}

	private static String shortRandom() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private record FlowFixture(Long userId, Long holdingId, String symbol) {
	}
}
