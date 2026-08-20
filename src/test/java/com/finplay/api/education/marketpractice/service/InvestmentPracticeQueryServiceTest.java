// InvestmentPracticeQueryService의 GET /api/education/practice 5가지 상태 판정을 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.market.domain.TutorialScenarioScriptId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.order.service.TradeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class InvestmentPracticeQueryServiceTest {

	private static final Long USER_ID = 1L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final FavoriteService favoriteService = mock(FavoriteService.class);
	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService = mock(
		PracticeAttemptEvidenceService.class);
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final ReferencePriceCalculator referencePriceCalculator = mock(ReferencePriceCalculator.class);
	private final PracticeMarketObservationRepository practiceMarketObservationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final PracticeCompletionRepository practiceCompletionRepository = mock(
		PracticeCompletionRepository.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final PracticeEntryComparisonService practiceEntryComparisonService = mock(
		PracticeEntryComparisonService.class);
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService = mock(
		PracticeStageProgressCalculationService.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final InvestmentPracticeQueryService service = new InvestmentPracticeQueryService(
		favoriteService, practiceAttemptRepository, practiceRiskSnapshotRepository, practiceAttemptEvidenceService,
		tradeService, chainResolutionService, referencePriceCalculator, practiceMarketObservationRepository,
		canonicalPriceService, practiceEntryComparisonService, practiceStageProgressCalculationService,
		practiceCompletionRepository, clock);

	// 프리셋 잠금 판정이 매 응답에서 순보유수량을 읽는다(042 EXITPRESET-003). 이 테스트들의 대상은 잠금이
	// 아니므로 기본을 "미보유"로 두고, 잠금을 보는 테스트만 따로 덮어쓴다.
	@BeforeEach
	void stubNoHolding() {
		when(tradeService.netFilledQuantity(anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
		// 041 6번의 진입별 대조는 attempt 경로에서만 얹히고 이 테스트들의 대상이 아니다 — 기본을 "없음"으로
		// 둔다. 배열의 내용은 PracticeEntryComparisonServiceTest와 통합 테스트가 본다.
		when(practiceEntryComparisonService.findCurrentRunEntries(any(), any())).thenReturn(List.of());
	}

	@Test
	void getProgressReturnsCompletedWithSharedEvidenceAcrossAllThreeStepsWhenCompletionExists() {
		Holding holding = holding(40L, 100L);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(10));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.tutorialKey()).isEqualTo(PracticeIntentionService.TUTORIAL_KEY);
		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.currentStep()).isNull();
		assertThat(response.completedAt()).isEqualTo(NOW);
		// 이슈 #343: 완료 응답은 보상 지급 금액 500만원을 노출해야 한다.
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.steps()).hasSize(3);
		for (PracticeStepResponse step : response.steps()) {
			assertThat(step.status()).isEqualTo("COMPLETED");
			assertThat(step.locked()).isFalse();
			assertThat(step.evidence().favoriteId()).isEqualTo(10L);
			assertThat(step.evidence().intentionId()).isEqualTo(20L);
			assertThat(step.evidence().buyTradeId()).isEqualTo(30L);
			assertThat(step.evidence().holdingId()).isEqualTo(40L);
			assertThat(step.evidence().observationId()).isEqualTo(60L);
			assertThat(step.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
			assertThat(step.evidence().reflectionId()).isEqualTo(50L);
			assertThat(step.evidence().reflectionCreatedAt()).isEqualTo(reflection.getCreatedAt());
		}
		// 완료 후에는 참조 가격선을 계산하지 않는다.
		assertThat(response.steps().get(0).evidence().referenceStopLossPrice()).isNull();
		assertThat(response.steps().get(0).evidence().referenceTakeProfitPrice()).isNull();
	}

	@Test
	void getProgressKeepsHoldingObservationAndReflectionButNullsFavoriteIntentionBuyTradeWhenChainLostOnRestart() {
		Holding holding = holding(40L, 100L);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		// 재시작으로 favorite/intention이 유실돼 chain 해석이 실패한다(빈 값).
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.empty());

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.TIMED_REPETITION,
			NOW.minusMinutes(10));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("COMPLETED");
		PracticeStepResponse firstStep = response.steps().get(0);
		assertThat(firstStep.evidence().favoriteId()).isNull();
		assertThat(firstStep.evidence().favoriteCreatedAt()).isNull();
		assertThat(firstStep.evidence().intentionId()).isNull();
		assertThat(firstStep.evidence().intentionCreatedAt()).isNull();
		assertThat(firstStep.evidence().buyTradeId()).isNull();
		assertThat(firstStep.evidence().buyTradeExecutedAt()).isNull();
		// holding·observation·reflection은 그대로 유지된다 — 완료 판정 자체는 흔들리지 않는다.
		assertThat(firstStep.evidence().holdingId()).isEqualTo(40L);
		assertThat(firstStep.evidence().observationId()).isEqualTo(60L);
		assertThat(firstStep.evidence().evidenceType()).isEqualTo("TIMED_REPETITION");
		assertThat(firstStep.evidence().reflectionId()).isEqualTo(50L);
	}

	@Test
	void getProgressReturnsInProgressStepThreeWithObservationEvidenceWhenChainHasQualifyingObservation() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain))
			.thenReturn(Optional.of(new ReferencePriceLines(new BigDecimal("90.00000000"),
				new BigDecimal("110.00000000"))));

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(5));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);
		assertThat(response.completedAt()).isNull();
		// 이슈 #343: 미완료(IN_PROGRESS) 응답은 보상 금액을 노출하지 않는다.
		assertThat(response.rewardAmount()).isNull();
		assertThat(response.steps()).hasSize(3);

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("COMPLETED");
		assertThat(step1.locked()).isFalse();
		assertThat(step1.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step1.evidence().intentionId()).isNull();

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("COMPLETED");
		assertThat(step2.evidence().intentionId()).isEqualTo(20L);
		assertThat(step2.evidence().buyTradeId()).isEqualTo(30L);
		assertThat(step2.evidence().holdingId()).isEqualTo(40L);
		assertThat(step2.evidence().referenceStopLossPrice()).isEqualByComparingTo("90.00000000");
		assertThat(step2.evidence().referenceTakeProfitPrice()).isEqualByComparingTo("110.00000000");
		assertThat(step2.evidence().observationId()).isNull();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("IN_PROGRESS");
		assertThat(step3.locked()).isFalse();
		assertThat(step3.evidence().holdingId()).isEqualTo(40L);
		assertThat(step3.evidence().observationId()).isEqualTo(60L);
		assertThat(step3.evidence().observationObservedAt()).isEqualTo(qualifying.getObservedAt());
		assertThat(step3.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
		assertThat(step3.evidence().reflectionId()).isNull();
	}

	@Test
	void getProgressReturnsInProgressStepThreeWithNullObservationFieldsWhenNoQualifyingObservationExists() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain))
			.thenReturn(Optional.of(new ReferencePriceLines(new BigDecimal("90.00000000"),
				new BigDecimal("110.00000000"))));

		// 관찰이 없거나(빈 리스트) evidenceType이 전부 null이면 qualifying observation이 없다.
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("IN_PROGRESS");
		// chain 필드는 그대로 채워지지만 observation 필드만 null이다.
		assertThat(step3.evidence().holdingId()).isEqualTo(40L);
		assertThat(step3.evidence().referenceStopLossPrice()).isEqualByComparingTo("90.00000000");
		assertThat(step3.evidence().observationId()).isNull();
		assertThat(step3.evidence().observationObservedAt()).isNull();
		assertThat(step3.evidence().evidenceType()).isNull();
	}

	@Test
	void getProgressReturnsInProgressStepTwoWithEarliestFavoriteWhenNoValidChainButFavoritesExistForMarket() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		FavoriteResponse laterFavorite = new FavoriteResponse(11L, 200L, "STOCK", "SYM2", "종목2", NOW.minusDays(1));
		FavoriteResponse earlierFavorite = new FavoriteResponse(10L, 100L, "STOCK", "SYM1", "종목1", NOW.minusDays(5));
		FavoriteResponse cryptoFavorite = new FavoriteResponse(12L, 300L, "CRYPTO", "SYM3", "종목3", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID))
			.thenReturn(new FavoriteListResponse(List.of(laterFavorite, earlierFavorite, cryptoFavorite)));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(2);
		assertThat(response.completedAt()).isNull();

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("COMPLETED");
		assertThat(step1.locked()).isFalse();
		// 여러 favorite 중 가장 이른 것(earlierFavorite, favoriteId=10)을 대표로 쓴다. crypto는 제외.
		assertThat(step1.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step1.evidence().favoriteCreatedAt()).isEqualTo(earlierFavorite.createdAt());

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("IN_PROGRESS");
		assertThat(step2.locked()).isFalse();
		assertThat(step2.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step2.evidence().intentionId()).isNull();
		assertThat(step2.evidence().buyTradeId()).isNull();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("NOT_STARTED");
		assertThat(step3.locked()).isTrue();
		assertThat(step3.evidence().favoriteId()).isNull();
	}

	@Test
	void getProgressReturnsNotStartedForAllStepsWhenNoCompletionChainOrFavoriteExists() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID,
			PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of()));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.CRYPTO);

		assertThat(response.tutorialKey()).isEqualTo(PracticeIntentionService.COIN_TUTORIAL_KEY);
		assertThat(response.status()).isEqualTo("NOT_STARTED");
		assertThat(response.currentStep()).isEqualTo(1);
		assertThat(response.completedAt()).isNull();
		// 이슈 #343: 미착수(NOT_STARTED) 응답도 보상 금액을 노출하지 않는다.
		assertThat(response.rewardAmount()).isNull();

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("NOT_STARTED");
		assertThat(step1.locked()).isFalse();
		assertThat(step1.evidence().favoriteId()).isNull();

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("NOT_STARTED");
		assertThat(step2.locked()).isTrue();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("NOT_STARTED");
		assertThat(step3.locked()).isTrue();
	}

	// (a) 샘플 종목 chain이 매도·복기까지 모두 완료되면 steps가 4개이고 4번째가 COMPLETED다(SANDBOX-005).
	@Test
	void getProgressReturnsFourStepsWithCompletedStepFourWhenSampleInstrumentChainFullyCompleted() {
		Holding holding = holding(40L, 100L, true);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		LocalDateTime buyExecutedAt = NOW.minusMinutes(10);
		LocalDateTime sellExecutedAt = NOW.minusMinutes(6);
		ResolvedPracticeChainDto chain = sampleChainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			buyExecutedAt, 40L, 35L, sellExecutedAt);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(9));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("COMPLETED");
		// 이슈 #343: 샘플 종목(4단계) chain의 완료 응답도 동일하게 500만원을 노출해야 한다.
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.steps()).hasSize(4);
		for (PracticeStepResponse step : response.steps()) {
			assertThat(step.status()).isEqualTo("COMPLETED");
		}
		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.evidence().sellTradeId()).isEqualTo(35L);
		assertThat(step4.evidence().sellTradeExecutedAt()).isEqualTo(sellExecutedAt);
		// (f) saleDeadlineAt은 buyTrade.executedAt + 5분과 정확히 일치해야 한다.
		assertThat(step4.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
	}

	// (b) 샘플 종목 chain에서 매수만 하고 매도 전, 아직 5분 이내면 steps가 4개이고 4번째는 대기 상태다
	// (SANDBOX-005·007). 구현은 이 대기 상태를 STATUS_AWAITING_SALE로 표현한다(locked=false) — NOT_STARTED를
	// 재사용하면 이 API의 다른 모든 NOT_STARTED가 locked=true와 짝을 이루는 관례와 충돌해 별도 값을 신설했다.
	@Test
	void getProgressReturnsFourStepsWithWaitingStepFourWhenSampleChainBoughtButNotSoldWithinFiveMinutes() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		LocalDateTime buyExecutedAt = NOW.minusMinutes(2);
		ResolvedPracticeChainDto chain = sampleChainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			buyExecutedAt, 40L, null, null);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(4);
		assertThat(response.steps()).hasSize(4);
		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.status()).isEqualTo("AWAITING_SALE");
		assertThat(step4.locked()).isFalse();
		assertThat(step4.evidence().sellTradeId()).isNull();
		// (f) saleDeadlineAt은 buyTrade.executedAt + 5분과 정확히 일치해야 한다.
		assertThat(step4.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
	}

	// (c) 매도 없이 5분을 초과하면 4번째가 EXPIRED다(SANDBOX-007).
	@Test
	void getProgressReturnsExpiredStepFourWhenSampleChainNotSoldPastFiveMinuteDeadline() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		LocalDateTime buyExecutedAt = NOW.minusMinutes(6);
		ResolvedPracticeChainDto chain = sampleChainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			buyExecutedAt, 40L, null, null);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.status()).isEqualTo("EXPIRED");
		assertThat(step4.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
	}

	// (d) 매도했지만 그 체결이 5분 초과 후라면 4번째가 EXPIRED다(SANDBOX-007 "매도 executedAt이 buyTrade.executedAt
	// + 5분 초과").
	@Test
	void getProgressReturnsExpiredStepFourWhenSampleChainSoldAfterFiveMinuteDeadline() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		LocalDateTime buyExecutedAt = NOW.minusMinutes(10);
		LocalDateTime lateSellExecutedAt = NOW.minusMinutes(4);
		ResolvedPracticeChainDto chain = sampleChainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			buyExecutedAt, 40L, 35L, lateSellExecutedAt);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.status()).isEqualTo("EXPIRED");
		assertThat(step4.evidence().sellTradeId()).isEqualTo(35L);
		assertThat(step4.evidence().sellTradeExecutedAt()).isEqualTo(lateSellExecutedAt);
	}

	// (e) 실제 종목 chain은 완료 여부와 무관하게 steps가 항상 3개다(SANDBOX-005 "실제 종목 chain은 026의 3단계
	// 응답을 그대로 유지한다").
	@Test
	void getProgressAlwaysReturnsThreeStepsForRealInstrumentChainRegardlessOfCompletion() {
		// 완료된 실제 종목 chain.
		Holding completedHolding = holding(41L, 101L, false);
		PracticeMarketReflection completedReflection = reflection(51L, completedHolding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(completedReflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 101L))
			.thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 41L))
			.thenReturn(List.of());

		InvestmentPracticeResponse completedResponse = service.getProgress(USER_ID, Market.STOCK);
		assertThat(completedResponse.steps()).hasSize(3);

		// 완료되지 않은(진행 중) 실제 종목 chain.
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID,
			PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		ResolvedPracticeChainDto chain = chainDto(11L, NOW.minusDays(3), 21L, NOW.minusDays(2), 31L,
			NOW.minusDays(1), 42L);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 42L))
			.thenReturn(List.of());

		InvestmentPracticeResponse inProgressResponse = service.getProgress(USER_ID, Market.CRYPTO);
		assertThat(inProgressResponse.steps()).hasSize(3);
	}

	// 관찰 필터 기준선은 riskSnapshot(최신 진입)이 아니라 observationBaseline(첫 진입)이어야 한다.
	// 두 필드에 서로 다른 createdAt을 넣고, 그 사이에 있는 관찰이 살아남는지로 소비 측 배선을 잠근다 —
	// riskSnapshot으로 되돌리면 이 관찰이 필터에서 잘려 3단계가 미완료로 떨어진다(이슈 #420과 같은 유형).
	@Test
	void getProgressFiltersObservationsByFirstEntryBaselineNotLatestEntry() {
		PracticeAttempt attempt = attempt(70L, 1L, PracticeAttemptStatus.IN_PROGRESS, instrument(100L));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));

		LocalDateTime firstEntryAt = NOW.minusMinutes(30);
		LocalDateTime latestEntryAt = NOW.minusMinutes(2);
		PracticeRiskSnapshot firstEntry = riskSnapshot(30L, firstEntryAt);
		PracticeRiskSnapshot latestEntry = riskSnapshot(31L, latestEntryAt);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(70L, 1L))
			.thenReturn(Optional.of(latestEntry));
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			latestEntry, firstEntry, 40L, new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("3"), null, null,
			null, null, null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, null)).thenReturn(resolved);
		// 첫 진입 이후·최신 진입 이전에 채운 관찰 — 기준선을 최신으로 잡으면 이 행이 사라진다.
		PracticeMarketObservation betweenEntries = observation(
			60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY, NOW.minusMinutes(10));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(betweenEntries));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.currentStep()).isEqualTo(4);
		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("COMPLETED");
		assertThat(step3.evidence().observationId()).isEqualTo(60L);
	}

	// 이슈 #426 (1): 완료 기록이 있어도 attempt가 재시작으로 진행 중이면 그 실행의 evidence를 돌려준다.
	// 예전 첫 분기는 이 조합을 예전 완료 응답으로 덮어써서 매수 사실·매도 기한이 프론트에 전달되지 않았다.
	@Test
	void getProgressReturnsRestartedRunEvidenceWhenCompletionExistsAndAttemptIsInProgress() {
		Holding holding = holding(40L, 100L, true);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusDays(1));
		PracticeCompletion completion = completion(reflection, NOW.minusDays(1));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		PracticeAttempt attempt = attempt(70L, 9L, PracticeAttemptStatus.IN_PROGRESS, instrument(100L));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));

		LocalDateTime buyExecutedAt = NOW.minusMinutes(2);
		PracticeRiskSnapshot snapshot = riskSnapshot(30L, buyExecutedAt);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(70L, 9L))
			.thenReturn(Optional.of(snapshot));
		// 이슈 #421의 매매 결과 4값(averageBuyPrice·averageSellPrice·realizedPnl·soldBuyBasis)은 이 테스트의 단정 대상이 아니라 null로 둔다 — 매도 전 상태이고 이 테스트는 단계·evidence 판정만 본다.
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, 40L, new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("3"), null, null, null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, null)).thenReturn(resolved);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);
		assertThat(response.steps()).hasSize(4);

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("COMPLETED");
		assertThat(step2.evidence().buyTradeId()).isEqualTo(30L);
		assertThat(step2.evidence().buyTradeExecutedAt()).isEqualTo(buyExecutedAt);
		assertThat(step2.evidence().buyQuantity()).isEqualByComparingTo("3");
		assertThat(step2.evidence().sellQuantity()).isEqualByComparingTo("0");
		assertThat(step2.evidence().remainingQuantity()).isEqualByComparingTo("3");
		assertThat(step2.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
		assertThat(step2.evidence().referenceStopLossPrice()).isEqualByComparingTo("97.00000000");
		assertThat(step2.evidence().referenceTakeProfitPrice()).isEqualByComparingTo("105.00000000");

		// 재시작한 실행의 risk snapshot이 attempt에 실려야 프론트가 매도 단계를 이어갈 수 있다(이슈 #426 증상).
		assertThat(response.attempt().attemptId()).isEqualTo(70L);
		assertThat(response.attempt().runNumber()).isEqualTo(9L);
		assertThat(response.attempt().mode()).isEqualTo("ACTIVE");
		assertThat(response.attempt().status()).isEqualTo("IN_PROGRESS");
		assertThat(response.attempt().riskSnapshot()).isNotNull();
		assertThat(response.attempt().riskSnapshot().buyTradeId()).isEqualTo(30L);

		// 040: 재시작해 다시 진행 중이어도 이미 받은 최초 완료 보상은 그대로 노출된다.
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.completedAt()).isEqualTo(NOW.minusDays(1));
	}

	// 041 SCENARIO-014 — 생성기 버전 2 attempt는 마감이 없으므로 saleDeadlineAt이 null로 내려가고, 매수 후
	// 아무리 오래 지나도 4단계가 EXPIRED가 되지 않는다. 프론트가 분기하는 것은 enum이 아니라 이 문자열이다.
	@Test
	void getProgressDropsSaleDeadlineAndNeverExpiresForScenarioAttempts() {
		// 대본이 저작된 시장은 CRYPTO뿐이다 — STOCK에 대본 실행을 세우면 프로덕션에 없는 조합이 된다.
		when(practiceCompletionRepository
			.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		PracticeAttempt attempt = attempt(70L, 1L, PracticeAttemptStatus.IN_PROGRESS, instrument(100L));
		when(attempt.getMarket()).thenReturn(Market.CRYPTO);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(attempt.usesScenarioScript()).thenReturn(true);
		// 041 6번 — 대본 실행이면 응답에 공개된 사건이 함께 실린다. 이 테스트의 대상은 마감 폐지이므로
		// 배포되는 대본을 그대로 물려 실제 게이트가 돌게 두고, 사건 목록 자체는 전용 테스트가 본다.
		when(canonicalPriceService.script(attempt))
			.thenReturn(
				new TutorialScenarioScriptLoader(new ObjectMapper()).script(TutorialScenarioScriptId.CRYPTO_STORY_V1));

		PracticeRiskSnapshot snapshot = riskSnapshot(30L, NOW.minusHours(3));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(70L, 1L))
			.thenReturn(Optional.of(snapshot));
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, 40L, new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("3"), null, null, null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, null)).thenReturn(resolved);
		PracticeMarketObservation observation = observation(
			60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY, NOW.minusHours(2));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(observation));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.CRYPTO);

		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.evidence().saleDeadlineAt()).isNull();
		assertThat(step4.status()).isEqualTo("AWAITING_SALE");
		assertThat(response.status()).isEqualTo("IN_PROGRESS");
	}

	// 이슈 #426 (2): 재시작 직후 종목 선택 단계에서도 최초 완료 기록의 보상 금액·완료 시각은 유지된다.
	@Test
	void getProgressKeepsFirstCompletionRewardWhenRestartedAttemptIsSelectingInstrument() {
		Holding holding = holding(40L, 100L, true);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusDays(1));
		PracticeCompletion completion = completion(reflection, NOW.minusDays(1));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		PracticeAttempt attempt = attempt(70L, 2L, PracticeAttemptStatus.SELECTING_INSTRUMENT, null);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(1);
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.completedAt()).isEqualTo(NOW.minusDays(1));
		assertThat(response.attempt().status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.attempt().instrumentId()).isNull();
	}

	// 이슈 #426 (3): attempt가 아예 없는 legacy 026 chain 완료자 응답은 이번 변경으로 달라지지 않는다.
	@Test
	void getProgressStillReturnsCompletedFallbackWhenCompletionExistsWithoutAttempt() {
		Holding holding = holding(40L, 100L);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.empty());
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.empty());
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.currentStep()).isNull();
		assertThat(response.completedAt()).isEqualTo(NOW);
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.steps()).hasSize(3);
		assertThat(response.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		assertThat(response.attempt()).isNull();
	}

	// 이슈 #426 (4): attempt가 COMPLETED인 replay 응답도 이번 변경 전과 동일하다.
	@Test
	void getProgressStillReturnsCompletedReplayWhenAttemptIsCompleted() {
		Holding holding = holding(40L, 100L, true);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		PracticeAttempt attempt = attempt(70L, 1L, PracticeAttemptStatus.COMPLETED, instrument(100L));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));

		LocalDateTime buyExecutedAt = NOW.minusMinutes(4);
		PracticeRiskSnapshot snapshot = riskSnapshot(30L, buyExecutedAt);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(70L, 1L))
			.thenReturn(Optional.of(snapshot));
		Trade sellTrade = mock(Trade.class);
		when(sellTrade.getId()).thenReturn(35L);
		when(sellTrade.getExecutedAt()).thenReturn(NOW.minusMinutes(1));
		// 이슈 #421의 매매 결과 4값은 이 테스트의 단정 대상이 아니라 null로 둔다 — 이 테스트는 replay 응답의 단계·evidence 불변만 본다.
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, 40L, new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO, sellTrade, null, null,
			null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, 40L)).thenReturn(resolved);
		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(3));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.currentStep()).isNull();
		assertThat(response.completedAt()).isEqualTo(NOW);
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.steps()).hasSize(4);
		assertThat(response.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
		assertThat(response.attempt().mode()).isEqualTo("REPLAY");
		assertThat(response.steps().get(3).evidence().sellTradeId()).isEqualTo(35L);
		assertThat(response.steps().get(3).evidence().observationId()).isEqualTo(60L);
	}

	// 이슈 #420: evidence를 가진 관찰이 매도 체결 이후에만 존재해도 진행 조회가 3단계를 완료로 보고 evidence를 채워야 한다. currentRunObservations가 매도 시각 이후 관찰을 배제하면 이 테스트만 깨진다.
	// 매도 전 관찰을 함께 두면 필터가 되살아나도 그 관찰로 통과해 버려 회귀를 못 잡으므로, evidence 관찰을 매도 이후 1건으로만 구성한다.
	@Test
	void getProgressFillsStepThreeEvidenceWhenOnlyObservationAfterSellHasEvidence() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		Instrument sampleInstrument = mock(Instrument.class);
		when(sampleInstrument.getId()).thenReturn(100L);
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(attempt.getId()).thenReturn(7L);
		when(attempt.getRunNumber()).thenReturn(1L);
		when(attempt.getMarket()).thenReturn(Market.STOCK);
		when(attempt.getStatus()).thenReturn(PracticeAttemptStatus.IN_PROGRESS);
		when(attempt.getInstrument()).thenReturn(sampleInstrument);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.of(attempt));

		LocalDateTime buyExecutedAt = NOW.minusMinutes(4);
		Trade buyTrade = mock(Trade.class);
		when(buyTrade.getId()).thenReturn(30L);
		when(buyTrade.getExecutedAt()).thenReturn(buyExecutedAt);
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getBuyTrade()).thenReturn(buyTrade);
		when(snapshot.getCreatedAt()).thenReturn(buyExecutedAt);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(7L, 1L))
			.thenReturn(Optional.of(snapshot));

		LocalDateTime sellExecutedAt = NOW.minusMinutes(3);
		Trade sellTrade = mock(Trade.class);
		when(sellTrade.getId()).thenReturn(35L);
		when(sellTrade.getExecutedAt()).thenReturn(sellExecutedAt);
		// 이슈 #421의 매매 결과 4값은 이 테스트의 단정 대상이 아니라 null로 둔다 — 이 fixture는 snapshot에 손절·익절가를 스텁하지 않아 어떤 체결가를 넣어도 sellVerdict가 null로 나오므로, 값을 지어내면 오히려 앞뒤가 안 맞는 tradeResult가 된다.
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, 40L, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, sellTrade, null, null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, null)).thenReturn(resolved);

		// observation(...) 헬퍼가 내부에서 mock·when을 호출하므로 바깥 when(...)이 .thenReturn()으로 닫히기 전에 실행되면 Mockito가 중첩 스터빙으로 보고 UnfinishedStubbingException을 던진다 — 이 파일의 다른 테스트들처럼 지역 변수로 먼저 뽑아 둔다.
		LocalDateTime observedAt = NOW.minusMinutes(1);
		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.TIMED_REPETITION, observedAt);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("COMPLETED");
		assertThat(step3.evidence().observationId()).isEqualTo(60L);
		assertThat(step3.evidence().observationObservedAt()).isEqualTo(observedAt);
		assertThat(step3.evidence().evidenceType()).isEqualTo("TIMED_REPETITION");
		assertThat(response.currentStep()).isEqualTo(4);
		assertThat(response.steps().get(3).status()).isEqualTo("IN_PROGRESS");
	}

	private static ResolvedPracticeChainDto chainDto(
		Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt,
		Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long holdingId) {
		return new ResolvedPracticeChainDto(
			favoriteId, favoriteCreatedAt, intentionId, intentionCreatedAt, new BigDecimal("90"),
			new BigDecimal("110"), buyTradeId, buyTradeExecutedAt, new BigDecimal("100"), holdingId, null, null, false);
	}

	// 샘플 종목 chain(4단계) 전용 — sellTradeId/sellTradeExecutedAt·instrumentIsTutorialSample=true를 채운다
	// (이슈 #339 tasks.md 4번).
	private static ResolvedPracticeChainDto sampleChainDto(
		Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt,
		Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long holdingId, Long sellTradeId,
		LocalDateTime sellTradeExecutedAt) {
		return new ResolvedPracticeChainDto(
			favoriteId, favoriteCreatedAt, intentionId, intentionCreatedAt, new BigDecimal("90"),
			new BigDecimal("110"), buyTradeId, buyTradeExecutedAt, new BigDecimal("100"), holdingId, sellTradeId,
			sellTradeExecutedAt, true);
	}

	// attempt 경로(039/040) 전용 mock — 재시작 실행의 id·세대·상태·선택 종목만 채운다.
	private static PracticeAttempt attempt(
		Long attemptId, long runNumber, PracticeAttemptStatus status, Instrument instrument) {
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(attempt.getId()).thenReturn(attemptId);
		when(attempt.getMarket()).thenReturn(Market.STOCK);
		when(attempt.getRunNumber()).thenReturn(runNumber);
		when(attempt.getStatus()).thenReturn(status);
		when(attempt.getInstrument()).thenReturn(instrument);
		return attempt;
	}

	private static PracticeRiskSnapshot riskSnapshot(Long buyTradeId, LocalDateTime buyExecutedAt) {
		Trade buyTrade = mock(Trade.class);
		when(buyTrade.getId()).thenReturn(buyTradeId);
		when(buyTrade.getExecutedAt()).thenReturn(buyExecutedAt);
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getBuyTrade()).thenReturn(buyTrade);
		when(snapshot.getEntryPrice()).thenReturn(new BigDecimal("100.00000000"));
		when(snapshot.getStopLossPrice()).thenReturn(new BigDecimal("97.00000000"));
		when(snapshot.getTakeProfitPrice()).thenReturn(new BigDecimal("105.00000000"));
		when(snapshot.getCreatedAt()).thenReturn(buyExecutedAt);
		return snapshot;
	}

	private static Instrument instrument(Long instrumentId) {
		Instrument instrument = Instrument.create(
			Market.STOCK, "SANDBOX_STK_1", "샘플종목", new BigDecimal("100"), 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", instrumentId);
		return instrument;
	}

	private static Holding holding(Long holdingId, Long instrumentId) {
		return holding(holdingId, instrumentId, false);
	}

	private static Holding holding(Long holdingId, Long instrumentId, boolean isTutorialSample) {
		Instrument instrument = Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", instrumentId);
		ReflectionTestUtils.setField(instrument, "tutorialSample", isTutorialSample);
		Account account = Account.create(
			User.create("trader@finplay.com", "password-hash", "trader", NOW),
			com.finplay.api.account.domain.Market.STOCK, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", holdingId);
		return holding;
	}

	private static PracticeMarketReflection reflection(Long reflectionId, Holding holding, LocalDateTime createdAt) {
		PracticeMarketReflection reflection = PracticeMarketReflection.create(
			USER_ID, holding, PracticeIntentionService.TUTORIAL_KEY, (short)1, "복기 내용", createdAt);
		ReflectionTestUtils.setField(reflection, "id", reflectionId);
		return reflection;
	}

	private static PracticeCompletion completion(PracticeMarketReflection reflection, LocalDateTime completedAt) {
		return PracticeCompletion.create(USER_ID, PracticeIntentionService.TUTORIAL_KEY, reflection, completedAt);
	}

	private static PracticeMarketObservation observation(
		Long observationId, PracticeEvidenceType evidenceType, LocalDateTime observedAt) {
		PracticeMarketObservation observation = mock(PracticeMarketObservation.class);
		when(observation.getId()).thenReturn(observationId);
		when(observation.getEvidenceType()).thenReturn(evidenceType);
		when(observation.getObservedAt()).thenReturn(observedAt);
		return observation;
	}
}
