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
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.service.PracticeLimitOrderService;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceSessionService;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceTickService;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
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
	private static final BigDecimal FALLBACK_START_PRICE = new BigDecimal("10000.00000000");
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("15000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("8000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("22000");

	private Long incompleteUserId;
	private Long completedUserId;
	private Long completedHoldingId;
	private LocalDateTime completedAt;
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
	private PracticePriceSessionService practicePriceSessionService;
	@Autowired
	private PracticeLimitOrderService practiceLimitOrderService;
	@Autowired
	private PracticePriceTickService practicePriceTickService;
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
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TestClock clock;
	@Autowired
	private PriceStore priceStore;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@AfterEach
	void clearFixtureLists() {}

	@AfterAll
	void cleanUpAllFixtures() {
		cleanUpCommittedFixtures();
		createdUserIds.clear();
		createdAccountIds.clear();
		createdInstrumentIds.clear();
		incompleteUserId = null;
		completedUserId = null;
		completedHoldingId = null;
		completedAt = null;
	}

	@Test
	@Order(1)
	void incompleteEvidenceExistsBeforeSpringContextIsRecreated() {
		FlowFixture fixture = createFilledLimitBuyChain("restart-incomplete");
		incompleteUserId = fixture.userId();

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

		clock.set(BASE_NOW.plusSeconds(10));
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
			Account.create(user, Market.CRYPTO, BASE_NOW));
		String symbol = "R" + shortRandom().toUpperCase();
		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, symbol, scenario, new BigDecimal("0.00000001"), 0L, true, BASE_NOW));
		createdUserIds.add(user.getId());
		createdAccountIds.add(account.getId());
		createdInstrumentIds.add(instrument.getId());

		User otherUser = userRepository.saveAndFlush(User.create(
			scenario + "-other-" + shortRandom() + "@finplay.com", "password-hash",
			scenario + "-other-" + shortRandom(), BASE_NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(otherUser, Market.CRYPTO, BASE_NOW));
		createdUserIds.add(otherUser.getId());
		createdAccountIds.add(otherAccount.getId());

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
		PracticePriceSessionResponse session = practicePriceSessionService.createSession(user.getId(),
			instrument.getId());
		assertThat(session.startPrice()).isEqualByComparingTo(FALLBACK_START_PRICE);
		PracticePriceSessionResponse otherSession = practicePriceSessionService.createSession(otherUser.getId(),
			instrument.getId());

		LimitOrderResponse pending = practiceLimitOrderService.createOrder(user.getId(),
			new PracticeLimitOrderCreateRequest(session.sessionId(), instrument.getId(), QUANTITY, LIMIT_PRICE));
		assertThat(pending.status()).isEqualTo("PENDING");
		LimitOrderResponse otherPending = practiceLimitOrderService.createOrder(otherUser.getId(),
			new PracticeLimitOrderCreateRequest(
				otherSession.sessionId(), instrument.getId(), QUANTITY, LIMIT_PRICE));
		LimitOrderResponse normalPending = limitOrderService.createLimitOrder(
			user.getId(), UUID.randomUUID().toString(),
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, QUANTITY, LIMIT_PRICE));

		FeedConnectionStatus statusBeforeTick = priceStore.getConnectionStatus();
		assertThat(priceStore.getLatestPrice(symbol))
			.as("실습 세션 tick 전에도 실제 PriceStore에는 이 실습 전용 심볼의 가격이 없다")
			.isEmpty();

		clock.set(BASE_NOW.plusSeconds(3));
		practicePriceTickService.advanceTick(user.getId(), session.sessionId(), 1);

		assertThat(priceStore.getLatestPrice(symbol))
			.as("실습 세션 tick 진행이 실제 PriceStore의 코인 가격을 채우지 않는다")
			.isEmpty();
		assertThat(priceStore.getConnectionStatus())
			.as("실습 세션 tick 진행이 실제 PriceStore의 연결 상태를 바꾸지 않는다")
			.isEqualTo(statusBeforeTick);

		assertThat(orderRepository.findById(pending.orderId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.FILLED);
		assertThat(orderRepository.findById(otherPending.orderId()).orElseThrow().getStatus())
			.as("다른 사용자의 세션 주문은 이 세션의 tick으로 체결되지 않는다")
			.isEqualTo(OrderStatus.PENDING);
		assertThat(orderRepository.findById(normalPending.orderId()).orElseThrow().getStatus())
			.as("세션에 귀속되지 않은 일반 주문은 세션 tick 이벤트로 체결되지 않는다")
			.isEqualTo(OrderStatus.PENDING);

		Holding holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holding.getQuantity()).isEqualByComparingTo(QUANTITY);
		InvestmentPracticeResponse filled = queryService.getProgress(user.getId(), Market.CRYPTO);
		assertThat(filled.status()).isEqualTo("IN_PROGRESS");
		assertThat(filled.currentStep()).isEqualTo(3);
		assertThat(filled.steps().get(1).status()).isEqualTo("COMPLETED");
		assertThat(filled.steps().get(2).status()).isEqualTo("IN_PROGRESS");
		return new FlowFixture(user.getId(), holding.getId());
	}

	private void cleanUpCommittedFixtures() {
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("DELETE FROM practice_completions WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_market_reflections WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_market_observations WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
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
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("DELETE FROM practice_price_sessions WHERE user_id = ?", userId);
		}
		createdInstrumentIds.forEach(
			instrumentId -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrumentId));
		createdUserIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
	}

	private static String shortRandom() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private record FlowFixture(Long userId, Long holdingId) {
	}
}
