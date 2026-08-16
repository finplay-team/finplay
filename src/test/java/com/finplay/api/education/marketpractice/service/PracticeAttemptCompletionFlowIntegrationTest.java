// 036 튜토리얼 attempt의 두 시장 완료·보상·재시작 세대 격리를 실제 MySQL로 검증하는 통합 테스트
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
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeAttemptCompletionFlowIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 14, 10, 0);
	private static final long COMPLETION_REWARD = 5_000_000L;

	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeAttemptRestartService practiceAttemptRestartService;
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
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private PracticeCompletionRepository completionRepository;
	@Autowired
	private PracticeMarketObservationRepository observationRepository;
	@Autowired
	private PracticeMarketReflectionRepository reflectionRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private TradeRepository tradeRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private EntityManager entityManager;

	@ParameterizedTest
	@EnumSource(Market.class)
	void attemptFlowCompletesAndReplayKeepsCurrentRunEvidenceAndRewardImmutable(Market market) {
		FlowFixture fixture = createFixture(market, "complete");
		BigDecimal buyQuantity = market == Market.STOCK ? new BigDecimal("10") : new BigDecimal("2.00000000");
		BigDecimal sellQuantity = market == Market.STOCK ? new BigDecimal("4") : new BigDecimal("1.00000000");

		PracticeAttemptResponse ensured = practiceAttemptService.ensureAttempt(fixture.userId(), market);
		assertThat(ensured.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(ensured.runNumber()).isEqualTo(1);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());

		clock.set(BASE_NOW.plusSeconds(2));
		OrderResponse buy = orderService.createOrder(
			fixture.userId(), idempotency("buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, buyQuantity));
		InvestmentPracticeResponse afterBuy = queryService.getProgress(fixture.userId(), market);
		PracticeEvidenceResponse buyEvidence = afterBuy.steps().get(1).evidence();
		assertThat(afterBuy.attempt().riskSnapshot()).isNotNull();
		assertThat(buyEvidence.favoriteId()).isNull();
		assertThat(buyEvidence.intentionId()).isNull();
		assertThat(buyEvidence.buyTradeId()).isEqualTo(buy.tradeId());
		assertThat(buyEvidence.buyQuantity()).isEqualByComparingTo(buyQuantity);
		assertThat(buyEvidence.sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(buyEvidence.remainingQuantity()).isEqualByComparingTo(buyQuantity);
		assertThat(afterBuy.attempt().riskSnapshot().entryPrice()).isEqualByComparingTo(buy.price());
		assertThat(afterBuy.attempt().riskSnapshot().stopLossPrice())
			.isEqualByComparingTo(buy.price().multiply(new BigDecimal("0.97")).setScale(8, RoundingMode.HALF_UP));
		assertThat(afterBuy.attempt().riskSnapshot().takeProfitPrice())
			.isEqualByComparingTo(buy.price().multiply(new BigDecimal("1.05")).setScale(8, RoundingMode.HALF_UP));

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), BASE_NOW.plusSeconds(12));

		clock.set(BASE_NOW.plusSeconds(150));
		OrderResponse sell = orderService.createOrder(
			fixture.userId(), idempotency("sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, sellQuantity));
		InvestmentPracticeResponse afterPartialSell = queryService.getProgress(fixture.userId(), market);
		PracticeEvidenceResponse sellEvidence = afterPartialSell.steps().get(3).evidence();
		assertThat(sellEvidence.sellTradeId()).isEqualTo(sell.tradeId());
		assertThat(sellEvidence.buyQuantity()).isEqualByComparingTo(buyQuantity);
		assertThat(sellEvidence.sellQuantity()).isEqualByComparingTo(sellQuantity);
		assertThat(sellEvidence.remainingQuantity()).isEqualByComparingTo(buyQuantity.subtract(sellQuantity));

		Account beforeReward = refreshedAccount(fixture.userId(), market);
		long cashBeforeReward = beforeReward.getCashBalance();
		long adjustmentBeforeReward = beforeReward.getSandboxCashAdjustment();
		clock.set(BASE_NOW.plusSeconds(160));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "현재 실행의 매매를 복기합니다."));

		Account rewarded = refreshedAccount(fixture.userId(), market);
		assertThat(rewarded.getCashBalance()).isEqualTo(cashBeforeReward + COMPLETION_REWARD);
		assertThat(rewarded.getSandboxCashAdjustment()).isEqualTo(adjustmentBeforeReward + COMPLETION_REWARD);
		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), market);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.rewardAmount()).isEqualTo(COMPLETION_REWARD);
		assertThat(completed.attempt().mode()).isEqualTo("REPLAY");
		assertThat(completed.steps()).hasSize(4).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		PracticeEvidenceResponse completedEvidence = completed.steps().get(3).evidence();
		assertThat(completedEvidence.favoriteId()).isNull();
		assertThat(completedEvidence.intentionId()).isNull();
		assertThat(completedEvidence.buyQuantity()).isEqualByComparingTo(buyQuantity);
		assertThat(completedEvidence.sellQuantity()).isEqualByComparingTo(sellQuantity);
		assertThat(completedEvidence.remainingQuantity()).isEqualByComparingTo(buyQuantity.subtract(sellQuantity));

		long orderCount = orderRepository.count();
		long tradeCount = tradeRepository.count();
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();
		long observationCount = observationRepository.count();
		PracticeAttemptResponse replayEnsure = practiceAttemptService.ensureAttempt(fixture.userId(), market);
		PracticeAttemptResponse replayRestart = practiceAttemptRestartService.restart(fixture.userId(), market);

		assertThat(replayEnsure.mode()).isEqualTo("REPLAY");
		assertThat(replayRestart.mode()).isEqualTo("REPLAY");
		assertThat(replayRestart.runNumber()).isEqualTo(1);
		assertThat(replayRestart.riskSnapshot()).isEqualTo(completed.attempt().riskSnapshot());
		assertThat(orderRepository.count()).isEqualTo(orderCount);
		assertThat(tradeRepository.count()).isEqualTo(tradeCount);
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
		assertThat(observationRepository.count()).isEqualTo(observationCount);
		Account replayed = refreshedAccount(fixture.userId(), market);
		assertThat(replayed.getCashBalance()).isEqualTo(rewarded.getCashBalance());
		assertThat(replayed.getSandboxCashAdjustment()).isEqualTo(rewarded.getSandboxCashAdjustment());
	}

	@Test
	void restartedRunRejectsStaleObservationAndSellOnReusedHoldingRow() {
		Market market = Market.CRYPTO;
		FlowFixture fixture = createFixture(market, "stale-run");
		BigDecimal quantity = new BigDecimal("2.00000000");
		practiceAttemptService.ensureAttempt(fixture.userId(), market);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("run1-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, quantity));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), BASE_NOW.plusSeconds(12));
		clock.set(BASE_NOW.plusSeconds(150));
		orderService.createOrder(fixture.userId(), idempotency("run1-sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, quantity));

		clock.set(BASE_NOW.plusSeconds(170));
		PracticeAttemptResponse restarted = practiceAttemptRestartService.restart(fixture.userId(), market);
		assertThat(restarted.runNumber()).isEqualTo(2);
		assertThat(restarted.instrumentId()).isNull();
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());
		clock.set(BASE_NOW.plusSeconds(180));
		orderService.createOrder(fixture.userId(), idempotency("run2-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, quantity));

		Holding reusedHolding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		assertThat(reusedHolding.getId()).isEqualTo(holding.getId());
		InvestmentPracticeResponse newRun = queryService.getProgress(fixture.userId(), market);
		PracticeEvidenceResponse newRunEvidence = newRun.steps().get(2).evidence();
		assertThat(newRunEvidence.observationId()).isNull();
		assertThat(newRunEvidence.sellTradeId()).isNull();
		assertThat(newRunEvidence.buyQuantity()).isEqualByComparingTo(quantity);
		assertThat(newRunEvidence.sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(newRunEvidence.remainingQuantity()).isEqualByComparingTo(quantity);
		assertThatThrownBy(() -> reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(reusedHolding.getId(), "이전 실행 증거로 완료 시도")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		createQualifyingObservations(fixture.userId(), reusedHolding.getId(), BASE_NOW.plusSeconds(190));
		assertThatThrownBy(() -> reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(reusedHolding.getId(), "현재 관찰만 있고 현재 매도 없음")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		clock.set(BASE_NOW.plusSeconds(320));
		orderService.createOrder(fixture.userId(), idempotency("run2-sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, new BigDecimal("1.00000000")));
		clock.set(BASE_NOW.plusSeconds(325));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(reusedHolding.getId(), "현재 실행 증거로 완료"));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), market);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.attempt().runNumber()).isEqualTo(2);
		PracticeEvidenceResponse evidence = completed.steps().get(3).evidence();
		assertThat(evidence.observationObservedAt()).isAfterOrEqualTo(BASE_NOW.plusSeconds(190));
		assertThat(evidence.sellTradeExecutedAt()).isAfterOrEqualTo(BASE_NOW.plusSeconds(320));
		assertThat(evidence.buyQuantity()).isEqualByComparingTo(quantity);
		assertThat(evidence.sellQuantity()).isEqualByComparingTo(new BigDecimal("1.00000000"));
		assertThat(evidence.remainingQuantity()).isEqualByComparingTo(new BigDecimal("1.00000000"));
	}

	private FlowFixture createFixture(Market market, String scenario) {
		clock.set(BASE_NOW);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "password-hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(Account.create(
			user, com.finplay.api.account.domain.Market.valueOf(market.name()), BASE_NOW));
		String symbol = market == Market.STOCK ? "SANDBOX_STK_1" : "SANDBOX_COIN_1";
		Instrument instrument = instrumentRepository.findByMarketAndSymbol(market, symbol).orElseThrow();
		return new FlowFixture(user.getId(), account.getId(), instrument.getId());
	}

	private void createQualifyingObservations(Long userId, Long holdingId, LocalDateTime firstAt) {
		clock.set(firstAt);
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(1));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(2));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private Account refreshedAccount(Long userId, Market market) {
		entityManager.flush();
		entityManager.clear();
		return accountRepository.findByUserIdAndMarket(
			userId, com.finplay.api.account.domain.Market.valueOf(market.name())).orElseThrow();
	}

	private static OrderCreateRequest marketOrder(
		Market market, Long instrumentId, OrderSide side, BigDecimal quantity) {
		return new OrderCreateRequest(market, instrumentId, side, "MARKET", quantity);
	}

	private static String idempotency(String scenario) {
		return scenario + "-" + UUID.randomUUID();
	}

	private record FlowFixture(Long userId, Long accountId, Long instrumentId) {
	}
}
