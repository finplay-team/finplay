// 이슈 #420 회귀: 샘플 종목을 전량 매도한 뒤에도 관찰이 계속 저장되고 복기 완료까지 이어지는지 실제 MySQL로 검증하는 통합 테스트
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

// PracticeAttemptCompletionFlowIntegrationTest와 같은 관례다 — 샘플 종목의 canonical 가격은 attempt seed에서
// 순수 계산되므로 Redis를 쓰지 않고, JPA(MySQL) 쓰기는 클래스 @Transactional 롤백으로 정리된다(별도 @AfterEach
// raw JdbcTemplate 정리가 필요한 경우는 트랜잭션 밖에서 도는 동시성 테스트뿐이다).
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

	/**
	 * 이슈 #420: 매수 → (관찰 없이) 전량 시장가 매도 → 매도 이후 관찰 3회로 evidence B(3회 + 2분 범위) 충족 → 복기 저장으로 완료까지 이어지는지 검증한다.
	 * 026 spec.md "비즈니스 규칙"이 관찰을 "holding이 존재하는 한 언제든 호출 가능하며 매도로 수량이 0이 되어도 계속 호출 가능"으로 못박았고 031 spec.md가 그 원칙을 그대로 상속하므로, 매도는 이 경로 어디에서도 차단 사유가 되어서는 안 된다.
	 */
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

		// 매수와 매도 사이에 관찰을 일부러 하나도 넣지 않는다 — 실제 사용자 흐름(매수 직후 자동 관찰 1회)과 다르지만, 그 관찰을 두면 evidence가 매도 전에 붙어버릴 수 있어 이 테스트가 회귀를 못 잡는다.
		// attempt price seed가 userId에서 파생돼 실행마다 가격 계열이 달라지므로 매수 직후 관찰이 evidence A(경계 접근)를 바로 충족하는 실행이 실제로 나왔다(이 테스트의 최초 버전이 그 자리에서 flaky하게 실패했다).
		// evidence가 오직 매도 이후에만 존재하는 상태를 만들어야 매도 이후 관찰을 배제하는 필터가 하나라도 되살아나면 반드시 깨진다 — 되돌리지 말 것.
		clock.set(BASE_NOW.plusSeconds(12));
		orderService.createOrder(fixture.userId(), idempotency("sell"),
			marketOrder(fixture.instrumentId(), OrderSide.SELL, QUANTITY));
		Holding soldOut = refreshedHolding(fixture.accountId(), fixture.instrumentId());
		assertThat(soldOut.getId()).isEqualTo(holding.getId());
		assertThat(soldOut.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

		// 매도 이후 관찰 3회. 이 호출들이 409 PRACTICE_STEP_LOCKED로 막히면 이슈 #420 회귀다.
		clock.set(BASE_NOW.plusSeconds(30));
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(90));
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
		clock.set(BASE_NOW.plusSeconds(150));
		PracticeHoldingObservationResponse qualifying = observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holding.getId()));

		// 3회 + 최초~최종 2분 범위를 채웠으므로 evidence가 붙는다. 판정은 A를 먼저 보므로 seed에 따라 CLOSER_TO_BOUNDARY가 붙을 수 있고, A가 미충족이면 B(TIMED_REPETITION)가 반드시 붙는다 — 특정 값으로 못박지 않는다.
		assertThat(qualifying.evidenceType()).isNotNull();
		assertThat(observationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(fixture.userId(), holding.getId()))
			.hasSize(3);

		// 진행 조회의 3단계 evidence — InvestmentPracticeQueryService가 매도 이후 관찰을 배제하면 여기서 null이 되어 깨진다.
		InvestmentPracticeResponse afterObservations = queryService.getProgress(fixture.userId(), MARKET);
		assertThat(afterObservations.steps().get(2).status()).isEqualTo("COMPLETED");
		PracticeEvidenceResponse observationEvidence = afterObservations.steps().get(2).evidence();
		assertThat(observationEvidence.observationId()).isNotNull();
		assertThat(observationEvidence.evidenceType()).isNotNull();
		assertThat(observationEvidence.observationObservedAt()).isAfter(BASE_NOW.plusSeconds(12));

		Account beforeReward = refreshedAccount(fixture.userId());
		long cashBeforeReward = beforeReward.getCashBalance();

		// 매도가 매수 체결 + 5분 안에 있었으므로 복기 저장이 완료를 확정해야 한다.
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
			user, com.finplay.api.account.domain.Market.valueOf(MARKET.name()), BASE_NOW));
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
			userId, com.finplay.api.account.domain.Market.valueOf(MARKET.name())).orElseThrow();
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
