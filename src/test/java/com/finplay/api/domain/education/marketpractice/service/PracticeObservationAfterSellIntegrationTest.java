package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeObservationAfterSellIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 17, 10, 0);
	private static final Market MARKET = Market.CRYPTO;
	private static final BigDecimal QUANTITY = new BigDecimal("2.00000000");
	private static final long COMPLETION_REWARD = 5_000_000L;

	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private OrderService orderService;
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
	private TestClock clock;
	@Autowired
	private EntityManager entityManager;

	@Test
	void observationsAfterFullSellStillSatisfyEvidenceAndCompleteTutorial() {
		Fixture fixture = createFixture();
		practiceAttemptService.ensureAttempt(fixture.userId(), MARKET);
		practiceAttemptService.selectInstrument(fixture.userId(), MARKET, fixture.instrumentId());

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("buy"),
			marketOrder(fixture.instrumentId(), OrderSide.BUY, QUANTITY));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();

		clock.set(BASE_NOW.plusSeconds(12));
		orderService.createOrder(fixture.userId(), idempotency("sell"),
			marketOrder(fixture.instrumentId(), OrderSide.SELL, QUANTITY));
		Holding soldOut = refreshedHolding(fixture.accountId(), fixture.instrumentId());
		assertThat(soldOut.getId()).isEqualTo(holding.getId());
		assertThat(soldOut.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

		clock.set(BASE_NOW.plusSeconds(30));
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(90));
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(150));
		PracticeHoldingObservationResponse qualifying = observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));

		assertThat(qualifying.evidenceType()).isNotNull();
		assertThat(observationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(fixture.userId(), holding.getId()))
			.hasSize(3);

		InvestmentPracticeResponse afterObservations = queryService.getProgress(fixture.userId(), MARKET);
		assertThat(afterObservations.steps().get(2).status()).isEqualTo("COMPLETED");
		PracticeEvidenceResponse observationEvidence = afterObservations.steps().get(2).evidence();
		assertThat(observationEvidence.observationId()).isNotNull();
		assertThat(observationEvidence.evidenceType()).isNotNull();
		assertThat(observationEvidence.observationObservedAt()).isAfter(BASE_NOW.plusSeconds(12));

		Account beforeReward = refreshedAccount(fixture.userId());
		long cashBeforeReward = beforeReward.getCashBalance();

		clock.set(BASE_NOW.plusSeconds(160));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "전량 매도 뒤 관찰로 evidence를 채우고 복기합니다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), MARKET);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.rewardAmount()).isEqualTo(COMPLETION_REWARD);
		assertThat(completed.steps()).hasSize(4)
			.allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		PracticeEvidenceResponse evidence = completed.steps().get(3).evidence();
		assertThat(evidence.sellQuantity()).isEqualByComparingTo(QUANTITY);
		assertThat(evidence.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(refreshedAccount(fixture.userId()).getCashBalance())
			.isEqualTo(cashBeforeReward + COMPLETION_REWARD);
	}

	private Fixture createFixture() {
		clock.set(BASE_NOW);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			"after-sell-" + suffix + "@finplay.com", "password-hash", "after-sell-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(Account.create(
			user, Market.valueOf(MARKET.name()), BASE_NOW));
		Instrument instrument = instrumentRepository
			.findByMarketAndSymbol(MARKET, "SANDBOX_COIN_1").orElseThrow();
		return new Fixture(user.getId(), account.getId(), instrument.getId());
	}

	private Holding refreshedHolding(Long accountId, Long instrumentId) {
		entityManager.flush();
		entityManager.clear();
		return holdingRepository.findByAccountIdAndInstrumentId(accountId, instrumentId).orElseThrow();
	}

	private Account refreshedAccount(Long userId) {
		entityManager.flush();
		entityManager.clear();
		return accountRepository.findByUserIdAndMarket(
			userId, Market.valueOf(MARKET.name())).orElseThrow();
	}

	private static OrderCreateRequest marketOrder(Long instrumentId, OrderSide side, BigDecimal quantity) {
		return new OrderCreateRequest(MARKET, instrumentId, side, "MARKET", quantity);
	}

	private static String idempotency(String scenario) {
		return scenario + "-" + UUID.randomUUID();
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId) {
	}
}
