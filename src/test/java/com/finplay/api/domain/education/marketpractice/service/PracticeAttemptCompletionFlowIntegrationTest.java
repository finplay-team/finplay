package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeRiskSnapshotResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTradeResultResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
		PracticeTradeResultResponse beforeSellResult = buyEvidence.tradeResult();
		assertThat(beforeSellResult).isNotNull();
		assertThat(beforeSellResult.buyPrice()).isEqualByComparingTo(afterBuy.attempt().riskSnapshot().entryPrice());
		assertThat(beforeSellResult.sellPrice()).isNull();
		assertThat(beforeSellResult.realizedPnl()).isNull();
		assertThat(beforeSellResult.returnRate()).isNull();
		assertThat(beforeSellResult.sellVerdict()).isNull();

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
		assertTradeResultMatchesLedger(
			sellEvidence.tradeResult(), buy, sell, afterPartialSell.attempt().riskSnapshot());

		Account beforeReward = refreshedAccount(fixture.userId(), market);
		long cashBeforeReward = beforeReward.getCashBalance();
		clock.set(BASE_NOW.plusSeconds(160));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "현재 실행의 매매를 복기합니다."));

		Account rewarded = refreshedAccount(fixture.userId(), market);
		assertThat(rewarded.getCashBalance()).isEqualTo(cashBeforeReward + COMPLETION_REWARD);
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
		assertTradeResultMatchesLedger(
			completedEvidence.tradeResult(), buy, sell, completed.attempt().riskSnapshot());

		long orderCount = orderRepository.count();
		long tradeCount = tradeRepository.count();
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();
		long observationCount = observationRepository.count();
		long cashBeforeRestart = rewarded.getCashBalance();
		Long attemptId = attemptRepository.findByUserIdAndMarket(fixture.userId(), market).orElseThrow().getId();
		PracticeAttemptResponse replayEnsure = practiceAttemptService.ensureAttempt(fixture.userId(), market);
		PracticeAttemptResponse replayRestart = practiceAttemptRestartService.restart(fixture.userId(), market);

		assertThat(replayEnsure.mode()).isEqualTo("REPLAY");
		assertThat(replayRestart.mode()).isEqualTo("ACTIVE");
		assertThat(replayRestart.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(replayRestart.runNumber()).isEqualTo(2);
		assertThat(replayRestart.instrumentId()).isNull();
		assertThat(replayRestart.riskSnapshot()).isNull();
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
		assertThat(observationRepository.count()).isEqualTo(observationCount);
		com.finplay.api.domain.order.entity.Order compensatingOrder = orderRepository
			.findByUserIdAndIdempotencyKey(fixture.userId(), "practice-restart:" + attemptId + ":1")
			.orElseThrow();
		assertThat(tradeRepository.findByOrderId(compensatingOrder.getId())).isPresent();
		assertThat(orderRepository.count()).isEqualTo(orderCount + 1);
		assertThat(tradeRepository.count()).isEqualTo(tradeCount + 1);
		Account replayed = refreshedAccount(fixture.userId(), market);
		assertThat(replayed.getCashBalance()).isEqualTo(cashBeforeRestart);
	}

	@Test
	void scenarioRunCompletesWithoutTimeGateEvenWhenSaleHappensLongAfterTheOldFiveMinuteDeadline() {
		Market market = Market.CRYPTO;
		FlowFixture fixture = createFixture(market, "no-time-gate");
		BigDecimal quantity = new BigDecimal("2.00000000");
		practiceAttemptService.ensureAttempt(fixture.userId(), market);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("late-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, quantity));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), BASE_NOW.plusSeconds(12));

		clock.set(BASE_NOW.plusHours(1));
		orderService.createOrder(fixture.userId(), idempotency("late-sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, quantity));

		InvestmentPracticeResponse beforeReflection = queryService.getProgress(fixture.userId(), market);
		assertThat(beforeReflection.status()).isNotEqualTo("EXPIRED");
		assertThat(beforeReflection.steps().get(3).evidence().saleDeadlineAt()).isNull();

		clock.set(BASE_NOW.plusHours(1).plusSeconds(10));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "한참 뒤에 팔았지만 막히지 않는다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), market);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.steps()).hasSize(4).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	@Test
	void restartedRunAfterCompletionReportsCurrentRunEvidenceAndKeepsFirstCompletionReward() {
		Market market = Market.CRYPTO;
		FlowFixture fixture = createFixture(market, "restart-progress");
		BigDecimal quantity = new BigDecimal("2.00000000");
		practiceAttemptService.ensureAttempt(fixture.userId(), market);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("first-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, quantity));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), BASE_NOW.plusSeconds(12));
		clock.set(BASE_NOW.plusSeconds(150));
		orderService.createOrder(fixture.userId(), idempotency("first-sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, new BigDecimal("1.00000000")));
		clock.set(BASE_NOW.plusSeconds(160));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), "최초 완료 복기입니다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), market);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.rewardAmount()).isEqualTo(COMPLETION_REWARD);
		LocalDateTime firstCompletedAt = completed.completedAt();
		assertThat(firstCompletedAt).isNotNull();
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();

		clock.set(BASE_NOW.plusSeconds(170));
		PracticeAttemptResponse restarted = practiceAttemptRestartService.restart(fixture.userId(), market);
		assertThat(restarted.runNumber()).isEqualTo(2);

		InvestmentPracticeResponse selecting = queryService.getProgress(fixture.userId(), market);
		assertThat(selecting.status()).isEqualTo("IN_PROGRESS");
		assertThat(selecting.attempt().status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(selecting.rewardAmount()).isEqualTo(COMPLETION_REWARD);
		assertThat(selecting.completedAt()).isEqualTo(firstCompletedAt);

		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());
		clock.set(BASE_NOW.plusSeconds(180));
		OrderResponse secondBuy = orderService.createOrder(fixture.userId(), idempotency("second-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, quantity));

		InvestmentPracticeResponse restartedProgress = queryService.getProgress(fixture.userId(), market);

		assertThat(restartedProgress.status()).isEqualTo("IN_PROGRESS");
		assertThat(restartedProgress.currentStep()).isEqualTo(3);
		assertThat(restartedProgress.steps()).hasSize(4);
		assertThat(restartedProgress.attempt().mode()).isEqualTo("ACTIVE");
		assertThat(restartedProgress.attempt().runNumber()).isEqualTo(2);
		assertThat(restartedProgress.attempt().riskSnapshot()).isNotNull();
		assertThat(restartedProgress.attempt().riskSnapshot().entryPrice()).isEqualByComparingTo(secondBuy.price());

		PracticeEvidenceResponse evidence = restartedProgress.steps().get(1).evidence();
		assertThat(evidence.buyTradeId()).isEqualTo(secondBuy.tradeId());
		assertThat(evidence.buyQuantity()).isEqualByComparingTo(quantity);
		assertThat(evidence.sellQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(evidence.remainingQuantity()).isEqualByComparingTo(quantity);
		assertThat(evidence.saleDeadlineAt()).isNull();
		assertThat(evidence.sellTradeId()).isNull();
		assertThat(evidence.observationId()).isNull();
		assertThat(restartedProgress.steps().get(3).status()).isEqualTo("NOT_STARTED");
		assertThat(restartedProgress.steps().get(3).locked()).isTrue();

		assertThat(restartedProgress.rewardAmount()).isEqualTo(COMPLETION_REWARD);
		assertThat(restartedProgress.completedAt()).isEqualTo(firstCompletedAt);
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
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

	@Test
	void fullSellAfterPartialSellReportsQuantityWeightedSellPriceAndSummedLedgerPnl() {
		Market market = Market.STOCK;
		FlowFixture fixture = createFixture(market, "weighted-sell");
		BigDecimal buyQuantity = new BigDecimal("10");
		BigDecimal firstSellQuantity = new BigDecimal("4");
		BigDecimal secondSellQuantity = new BigDecimal("6");

		practiceAttemptService.ensureAttempt(fixture.userId(), market);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());
		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("weighted-buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, buyQuantity));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), BASE_NOW.plusSeconds(12));

		clock.set(BASE_NOW.plusSeconds(150));
		OrderResponse firstSell = orderService.createOrder(fixture.userId(), idempotency("weighted-sell-1"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, firstSellQuantity));
		clock.set(BASE_NOW.plusSeconds(220));
		OrderResponse secondSell = orderService.createOrder(fixture.userId(), idempotency("weighted-sell-2"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, secondSellQuantity));

		InvestmentPracticeResponse afterFullSell = queryService.getProgress(fixture.userId(), market);
		PracticeTradeResultResponse tradeResult = afterFullSell.steps().get(3).evidence().tradeResult();
		assertThat(tradeResult).isNotNull();

		BigDecimal expectedSellPrice = firstSell.price().multiply(firstSellQuantity)
			.add(secondSell.price().multiply(secondSellQuantity))
			.divide(firstSellQuantity.add(secondSellQuantity), 8, RoundingMode.HALF_UP);
		assertThat(tradeResult.sellPrice()).isEqualByComparingTo(expectedSellPrice);
		assertThat(firstSell.realizedPnl()).isNotNull();
		assertThat(secondSell.realizedPnl()).isNotNull();
		long expectedRealizedPnl = firstSell.realizedPnl() + secondSell.realizedPnl();
		assertThat(tradeResult.realizedPnl()).isEqualTo(expectedRealizedPnl);
		long expectedBasis = (firstSell.amount() - firstSell.fee() - firstSell.realizedPnl())
			+ (secondSell.amount() - secondSell.fee() - secondSell.realizedPnl());
		assertThat(expectedBasis).isPositive();
		assertThat(tradeResult.returnRate()).isEqualByComparingTo(
			BigDecimal.valueOf(expectedRealizedPnl).divide(BigDecimal.valueOf(expectedBasis), 4, RoundingMode.HALF_UP));
		assertThat(firstSell.fee() + secondSell.fee()).isPositive();
		assertThat(afterFullSell.steps().get(3).evidence().remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeResult.sellVerdict()).isIn("ABOVE_TAKE_PROFIT", "BELOW_STOP_LOSS", "BETWEEN_LINES");
		assertThat(tradeResult.sellVerdict())
			.isEqualTo(expectedVerdict(tradeResult.sellPrice(), afterFullSell.attempt().riskSnapshot()));
	}

	private static void assertTradeResultMatchesLedger(
		PracticeTradeResultResponse tradeResult,
		OrderResponse buy,
		OrderResponse sell,
		PracticeRiskSnapshotResponse riskSnapshot) {
		assertThat(tradeResult).isNotNull();
		assertThat(tradeResult.buyPrice()).isEqualByComparingTo(buy.price());
		assertThat(tradeResult.buyPrice()).isEqualByComparingTo(riskSnapshot.entryPrice());
		assertThat(tradeResult.sellPrice()).isEqualByComparingTo(sell.price());
		assertThat(sell.realizedPnl()).isNotNull();
		assertThat(tradeResult.realizedPnl()).isEqualTo(sell.realizedPnl());
		long expectedBasis = sell.amount() - sell.fee() - sell.realizedPnl();
		assertThat(expectedBasis).isPositive();
		assertThat(tradeResult.returnRate()).isEqualByComparingTo(
			BigDecimal.valueOf(sell.realizedPnl()).divide(BigDecimal.valueOf(expectedBasis), 4, RoundingMode.HALF_UP));
		assertThat(tradeResult.sellVerdict()).isEqualTo(expectedVerdict(tradeResult.sellPrice(), riskSnapshot));
	}

	private static String expectedVerdict(BigDecimal sellPrice, PracticeRiskSnapshotResponse riskSnapshot) {
		if (sellPrice.compareTo(riskSnapshot.takeProfitPrice()) >= 0) {
			return "ABOVE_TAKE_PROFIT";
		}
		if (sellPrice.compareTo(riskSnapshot.stopLossPrice()) <= 0) {
			return "BELOW_STOP_LOSS";
		}
		return "BETWEEN_LINES";
	}

	private FlowFixture createFixture(Market market, String scenario) {
		clock.set(BASE_NOW);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "password-hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(Account.create(
			user, Market.valueOf(market.name()), BASE_NOW));
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
			userId, Market.valueOf(market.name())).orElseThrow();
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
