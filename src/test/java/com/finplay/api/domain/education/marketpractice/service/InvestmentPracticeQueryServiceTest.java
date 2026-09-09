package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
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
	private final PracticeExitPlanReservationService practiceExitPlanReservationService = mock(
		PracticeExitPlanReservationService.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final InvestmentPracticeQueryService service = new InvestmentPracticeQueryService(
		favoriteService, practiceAttemptRepository, practiceRiskSnapshotRepository, practiceAttemptEvidenceService,
		tradeService, chainResolutionService, referencePriceCalculator, practiceMarketObservationRepository,
		canonicalPriceService, practiceEntryComparisonService, practiceStageProgressCalculationService,
		practiceExitPlanReservationService, practiceCompletionRepository, clock);

	@BeforeEach
	void stubNoHolding() {
		when(tradeService.netFilledQuantity(anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
		when(practiceEntryComparisonService.findCurrentRunEntries(any(), any())).thenReturn(List.of());
		when(practiceExitPlanReservationService.view(any())).thenReturn(PracticeExitPlanViewDto.none());
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

		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("IN_PROGRESS");
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
		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.steps()).hasSize(4);
		for (PracticeStepResponse step : response.steps()) {
			assertThat(step.status()).isEqualTo("COMPLETED");
		}
		PracticeStepResponse step4 = response.steps().get(3);
		assertThat(step4.evidence().sellTradeId()).isEqualTo(35L);
		assertThat(step4.evidence().sellTradeExecutedAt()).isEqualTo(sellExecutedAt);
		assertThat(step4.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
	}

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
		assertThat(step4.evidence().saleDeadlineAt()).isEqualTo(buyExecutedAt.plusMinutes(5));
	}

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

	@Test
	void getProgressAlwaysReturnsThreeStepsForRealInstrumentChainRegardlessOfCompletion() {
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

		assertThat(response.attempt().attemptId()).isEqualTo(70L);
		assertThat(response.attempt().runNumber()).isEqualTo(9L);
		assertThat(response.attempt().mode()).isEqualTo("ACTIVE");
		assertThat(response.attempt().status()).isEqualTo("IN_PROGRESS");
		assertThat(response.attempt().riskSnapshot()).isNotNull();
		assertThat(response.attempt().riskSnapshot().buyTradeId()).isEqualTo(30L);

		assertThat(response.rewardAmount()).isEqualTo(5_000_000L);
		assertThat(response.completedAt()).isEqualTo(NOW.minusDays(1));
	}

	@Test
	void getProgressDropsSaleDeadlineAndNeverExpiresForScenarioAttempts() {
		when(practiceCompletionRepository
			.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		PracticeAttempt attempt = attempt(70L, 1L, PracticeAttemptStatus.IN_PROGRESS, instrument(100L));
		when(attempt.getMarket()).thenReturn(Market.CRYPTO);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(attempt.usesScenarioScript()).thenReturn(true);
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

	@Test
	void getProgressFillsStepThreeEvidenceWhenOnlyObservationAfterSellHasEvidence() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		Instrument sampleInstrument = mock(Instrument.class);
		when(sampleInstrument.getId()).thenReturn(100L);
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(attempt.effectiveExitRates()).thenReturn(ExitRates.DEFAULT);
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
		when(snapshot.appliedExitRates()).thenReturn(ExitRates.DEFAULT);
		when(snapshot.getBuyTrade()).thenReturn(buyTrade);
		when(snapshot.getCreatedAt()).thenReturn(buyExecutedAt);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(7L, 1L))
			.thenReturn(Optional.of(snapshot));

		LocalDateTime sellExecutedAt = NOW.minusMinutes(3);
		Trade sellTrade = mock(Trade.class);
		when(sellTrade.getId()).thenReturn(35L);
		when(sellTrade.getExecutedAt()).thenReturn(sellExecutedAt);
		ResolvedPracticeAttemptEvidenceDto resolved = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, 40L, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, sellTrade, null, null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, null)).thenReturn(resolved);

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

	private static ResolvedPracticeChainDto sampleChainDto(
		Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt,
		Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long holdingId, Long sellTradeId,
		LocalDateTime sellTradeExecutedAt) {
		return new ResolvedPracticeChainDto(
			favoriteId, favoriteCreatedAt, intentionId, intentionCreatedAt, new BigDecimal("90"),
			new BigDecimal("110"), buyTradeId, buyTradeExecutedAt, new BigDecimal("100"), holdingId, sellTradeId,
			sellTradeExecutedAt, true);
	}

	private static PracticeAttempt attempt(
		Long attemptId, long runNumber, PracticeAttemptStatus status, Instrument instrument) {
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(attempt.effectiveExitRates()).thenReturn(ExitRates.DEFAULT);
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
		when(snapshot.appliedExitRates()).thenReturn(ExitRates.DEFAULT);
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
			Market.STOCK, NOW);
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
