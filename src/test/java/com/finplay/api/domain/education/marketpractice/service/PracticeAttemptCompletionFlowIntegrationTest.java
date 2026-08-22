// 036 튜토리얼 attempt의 두 시장 완료·보상·재시작 세대 격리를 실제 MySQL로 검증하는 통합 테스트
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
		// 이슈 #421: 매도 전에는 buyPrice만 채워지고, 매수 체결이 1건이므로 riskSnapshot.entryPrice와 정확히
		// 같아야 한다 — 두 값이 갈라지면 같은 화면에서 매수가가 두 개로 보인다.
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
		// 이슈 #421: 부분 매도 직후 tradeResult가 원장 값과 일치해야 한다. attempt 가격 seed가 userId에서
		// 파생돼 실행마다 가격 계열이 달라지므로 가격을 하드코딩하지 않고 주문 응답이 돌려준 체결 원장과의
		// 관계로만 단정한다.
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
		// 완료 REPLAY 재조회도 같은 값을 그대로 돌려준다(api-contracts.md 039 TUTORIAL-FLOW-013).
		assertTradeResultMatchesLedger(
			completedEvidence.tradeResult(), buy, sell, completed.attempt().riskSnapshot());

		long orderCount = orderRepository.count();
		long tradeCount = tradeRepository.count();
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();
		long observationCount = observationRepository.count();
		// rewarded는 이후 restart()가 재사용할 수 있는 영속성 컨텍스트에 attach된 엔티티다 — 원시값으로
		// 미리 뽑아두지 않으면 restart의 account 변경이 이 참조에도 그대로 반영돼(같은 세션 identity map)
		// "이전 값"이 오염된다.
		long cashBeforeRestart = rewarded.getCashBalance();
		Long attemptId = attemptRepository.findByUserIdAndMarket(fixture.userId(), market).orElseThrow().getId();
		PracticeAttemptResponse replayEnsure = practiceAttemptService.ensureAttempt(fixture.userId(), market);
		PracticeAttemptResponse replayRestart = practiceAttemptRestartService.restart(fixture.userId(), market);

		// ensureAttempt는 완료된 attempt를 여전히 REPLAY로 유지한다(TUTORIAL-RESTART-002, 요구사항 불변).
		assertThat(replayEnsure.mode()).isEqualTo("REPLAY");
		// restart는 이제 완료된 attempt도 실제로 정리·재시작한다(TUTORIAL-RESTART-001).
		assertThat(replayRestart.mode()).isEqualTo("ACTIVE");
		assertThat(replayRestart.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(replayRestart.runNumber()).isEqualTo(2);
		assertThat(replayRestart.instrumentId()).isNull();
		assertThat(replayRestart.riskSnapshot()).isNull();
		// 재시작은 completion/reflection/observation evidence는 건드리지 않는다.
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
		assertThat(observationRepository.count()).isEqualTo(observationCount);
		// 재시작 시점에 남아 있던 보유 잔량(매수-매도)은 정리 로직이 보상 매도 주문·체결 1건으로 청산한다.
		com.finplay.api.domain.order.entity.Order compensatingOrder = orderRepository
			.findByUserIdAndIdempotencyKey(fixture.userId(), "practice-restart:" + attemptId + ":1")
			.orElseThrow();
		assertThat(tradeRepository.findByOrderId(compensatingOrder.getId())).isPresent();
		assertThat(orderRepository.count()).isEqualTo(orderCount + 1);
		assertThat(tradeRepository.count()).isEqualTo(tradeCount + 1);
		// 보상매도는 튜토리얼 종목 매도이므로 PortfolioSellService.finalizeSellRealizedPnl이 같은 사용자·
		// 시장의 튜토리얼 계좌만 갱신한다(047 TUTORIAL-CASH-ISOL-003) — 실제 Account.cashBalance는 전혀
		// 변하지 않는다.
		Account replayed = refreshedAccount(fixture.userId(), market);
		assertThat(replayed.getCashBalance()).isEqualTo(cashBeforeRestart);
	}

	// 041 SCENARIO-014, 이슈 #472: 시간 제한 폐지의 실제 강제 지점은 복기 저장이다. 생성기 버전 2 실행은
	// 매수 후 한 시간이 지나 매도해도 409로 막히지 않고, 진행 조회의 마감 값도 내려가지 않는다.
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

		// 옛 마감(매수 + 5분)을 한참 넘긴 시각에 매도한다.
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

	// 이슈 #426: 완료한 시장을 040 재시작으로 다시 진행하면 진행 조회가 예전 완료 응답이 아니라 현재 실행의
	// evidence를 돌려줘야 한다. 최초 완료 기록은 남아 있으므로 rewardAmount·completedAt은 그대로 유지된다.
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
		// 부분 매도로 완료한다 — 남은 잔량은 재시작 정리가 보상 매도로 청산한다(039 재시작 규칙, 위 테스트와 동일).
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

		// 재시작 직후(종목 선택 전)에도 진행 조회는 완료 응답이 아니라 현재 실행 상태를 돌려준다.
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
		// 041 SCENARIO-014: 대본을 쓰는 CRYPTO 실행은 마감이 없다 — 이 값이 null이어야 프론트가 남은 시간
		// 표시를 숨긴다(이슈 #472).
		assertThat(evidence.saleDeadlineAt()).isNull();
		// 이전 실행의 매도·관찰은 현재 실행 evidence가 아니다.
		assertThat(evidence.sellTradeId()).isNull();
		assertThat(evidence.observationId()).isNull();
		// 현재 실행에는 아직 qualifying 관찰이 없으므로 4단계는 잠긴 미착수다(039 attempt 경로 계약).
		assertThat(restartedProgress.steps().get(3).status()).isEqualTo("NOT_STARTED");
		assertThat(restartedProgress.steps().get(3).locked()).isTrue();

		// 040: 재시작해 다시 진행 중이어도 최초 완료 기록과 이미 받은 보상은 그대로다(재지급도 없다).
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

	// 이슈 #421: 부분 매도 뒤 잔량까지 전량 매도하면 SELL 체결이 2건이 된다. 이 경우 sellPrice가 특정 체결
	// 1건의 가격이 아니라 수량 가중평균이어야 하고, realizedPnl·수익률 분모도 두 체결의 합이어야 한다.
	// 기존 파라미터 테스트는 매도 1건 흐름이라 "가중"이 걸려 있는지를 드러내지 못해 별도 시나리오로 둔다.
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
		// 매도 단가로 손익을 다시 계산한 값과는 수수료만큼 달라야 한다 — realizedPnl이 수수료 차감 후 순손익이라는
		// 계약(api-contracts.md)이 실제로 지켜지는지 확인한다.
		assertThat(firstSell.fee() + secondSell.fee()).isPositive();
		assertThat(afterFullSell.steps().get(3).evidence().remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeResult.sellVerdict()).isIn("ABOVE_TAKE_PROFIT", "BELOW_STOP_LOSS", "BETWEEN_LINES");
		assertThat(tradeResult.sellVerdict())
			.isEqualTo(expectedVerdict(tradeResult.sellPrice(), afterFullSell.attempt().riskSnapshot()));
	}

	// tradeResult 다섯 필드를 매수·매도 주문 응답(=체결 원장)에서 그대로 유도해 비교한다. 매수 1건·매도 1건
	// 흐름 전용이다.
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

	// 서버 판정 규칙(양 끝 포함, 익절선 우선)의 기대값을 스냅샷 기준선에서 유도한다 — 가격 계열이 seed에 따라
	// 달라지므로 특정 판정값을 고정할 수 없다. 경계 자체의 세부 규칙은 PracticeTradeResultCalculatorTest가 본다.
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
