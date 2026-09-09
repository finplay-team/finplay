package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvestmentPracticeQueryService {

	private static final String STATUS_COMPLETED = "COMPLETED";
	private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
	private static final String STATUS_NOT_STARTED = "NOT_STARTED";
	private static final String STATUS_EXPIRED = "EXPIRED";
	private static final String STATUS_AWAITING_SALE = "AWAITING_SALE";
	private static final long SALE_DEADLINE_MINUTES = 5;
	private static final long TUTORIAL_COMPLETION_REWARD_AMOUNT = 5_000_000L;

	private final FavoriteService favoriteService;
	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService;
	private final TradeService tradeService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeEntryComparisonService practiceEntryComparisonService;
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService;
	private final PracticeExitPlanReservationService practiceExitPlanReservationService;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public InvestmentPracticeResponse getProgress(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);

		Optional<PracticeCompletion> completion = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey);
		Optional<PracticeAttempt> attempt = practiceAttemptRepository.findByUserIdAndMarket(userId, market);
		if (attempt.isPresent()) {
			if (attempt.get().getStatus() == PracticeAttemptStatus.COMPLETED) {
				PracticeCompletion completed = completion
					.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
				if (practiceRiskSnapshotRepository
					.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(
						attempt.get().getId(), attempt.get().getRunNumber())
					.isEmpty()) {
					return withEntryComparison(attachReplayAttempt(
						buildCompletedResponse(userId, tutorialKey, completed), attempt.get()), attempt.get());
				}
				return withEntryComparison(buildCompletedAttemptResponse(
					userId,
					tutorialKey,
					attempt.get(),
					completed), attempt.get());
			}
			return withEntryComparison(
				buildActiveAttemptResponse(userId, tutorialKey, attempt.get(), completion.orElse(null)),
				attempt.get());
		}
		if (completion.isPresent()) {
			return buildCompletedResponse(userId, tutorialKey, completion.get());
		}

		Optional<ResolvedPracticeChainDto> chain = chainResolutionService.resolve(userId, tutorialKey);
		if (chain.isPresent()) {
			return buildChainResponse(userId, tutorialKey, chain.get());
		}

		List<FavoriteResponse> marketFavorites = favoriteService.getFavorites(userId).content().stream()
			.filter(favorite -> market.name().equals(favorite.market()))
			.toList();
		if (!marketFavorites.isEmpty()) {
			return buildFavoriteOnlyResponse(tutorialKey, marketFavorites);
		}

		return buildNotStartedResponse(tutorialKey);
	}

	private InvestmentPracticeResponse withEntryComparison(
		InvestmentPracticeResponse response, PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return response;
		}
		BigDecimal priceAfterSell = canonicalPriceService.postSellComparisonPrice(attempt);
		List<PracticeScenarioEventResponse> revealedEvents = attempt.usesScenarioScript()
			? PracticeScenarioNarrativeCalculator
				.calculate(attempt, canonicalPriceService.script(attempt))
				.revealedEvents()
			: List.of();
		PracticeExitPlanViewDto exitPlanView = practiceExitPlanReservationService.view(attempt);
		return new InvestmentPracticeResponse(
			response.tutorialKey(),
			response.status(),
			response.currentStep(),
			response.steps(),
			response.completedAt(),
			response.rewardAmount(),
			response.attempt(),
			revealedEvents,
			priceAfterSell,
			practiceEntryComparisonService.findCurrentRunEntries(attempt, priceAfterSell),
			practiceStageProgressCalculationService.calculate(attempt),
			exitPlanView.creatable(),
			exitPlanView.pendingExitPlan(),
			exitPlanView.experience());
	}

	private InvestmentPracticeResponse attachReplayAttempt(
		InvestmentPracticeResponse response, PracticeAttempt attempt) {
		return new InvestmentPracticeResponse(
			response.tutorialKey(),
			response.status(),
			response.currentStep(),
			response.steps(),
			response.completedAt(),
			response.rewardAmount(),
			PracticeAttemptResponse.from(attempt, null, exitPresetLocked(attempt)));
	}

	private InvestmentPracticeResponse buildActiveAttemptResponse(
		Long userId, String tutorialKey, PracticeAttempt attempt, PracticeCompletion completion) {
		LocalDateTime completedAt = completion == null ? null : completion.getCompletedAt();
		Long rewardAmount = completion == null ? null : TUTORIAL_COMPLETION_REWARD_AMOUNT;
		PracticeAttemptResponse attemptResponse;
		if (attempt.getInstrument() == null) {
			attemptResponse = PracticeAttemptResponse.from(attempt, null, false);
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_IN_PROGRESS, false, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(2, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(4, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
			return new InvestmentPracticeResponse(
				tutorialKey, STATUS_IN_PROGRESS, 1, steps, completedAt, rewardAmount, attemptResponse);
		}

		Optional<PracticeRiskSnapshot> snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(
				attempt.getId(), attempt.getRunNumber());
		attemptResponse = PracticeAttemptResponse.from(attempt, snapshot.orElse(null), exitPresetLocked(attempt));
		if (snapshot.isEmpty()) {
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_COMPLETED, false, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(2, STATUS_IN_PROGRESS, false, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()),
				new PracticeStepResponse(4, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
			return new InvestmentPracticeResponse(
				tutorialKey, STATUS_IN_PROGRESS, 2, steps, completedAt, rewardAmount, attemptResponse);
		}

		ResolvedPracticeAttemptEvidenceDto resolved = practiceAttemptEvidenceService
			.requireCurrentRun(attempt, userId, null);
		Optional<PracticeMarketObservation> qualifyingObservation = currentRunObservations(userId, resolved).stream()
			.filter(observation -> observation.getEvidenceType() != null)
			.findFirst();
		LocalDateTime saleDeadlineAt = attemptSaleDeadlineAt(attempt, snapshot.get());
		PracticeEvidenceResponse evidence = attemptEvidence(resolved, qualifyingObservation.orElse(null),
			saleDeadlineAt,
			null);

		String stepFourStatus;
		boolean stepFourLocked;
		if (qualifyingObservation.isEmpty()) {
			stepFourStatus = STATUS_NOT_STARTED;
			stepFourLocked = true;
		} else if (resolved.sellTrade() != null) {
			stepFourStatus = isWithinSaleDeadline(resolved.sellTrade().getExecutedAt(), saleDeadlineAt)
				? STATUS_IN_PROGRESS
				: STATUS_EXPIRED;
			stepFourLocked = false;
		} else {
			stepFourStatus = isWithinSaleDeadline(LocalDateTime.now(clock), saleDeadlineAt)
				? STATUS_AWAITING_SALE
				: STATUS_EXPIRED;
			stepFourLocked = false;
		}
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(
				3, qualifyingObservation.isPresent() ? STATUS_COMPLETED : STATUS_IN_PROGRESS, false, evidence),
			new PracticeStepResponse(4, stepFourStatus, stepFourLocked, evidence));
		String overallStatus = STATUS_EXPIRED.equals(stepFourStatus) ? STATUS_EXPIRED : STATUS_IN_PROGRESS;
		return new InvestmentPracticeResponse(
			tutorialKey, overallStatus, qualifyingObservation.isPresent() ? 4 : 3, steps, completedAt, rewardAmount,
			attemptResponse);
	}

	private InvestmentPracticeResponse buildCompletedAttemptResponse(
		Long userId, String tutorialKey, PracticeAttempt attempt, PracticeCompletion completion) {
		ResolvedPracticeAttemptEvidenceDto resolved = practiceAttemptEvidenceService
			.requireCurrentRun(attempt, userId, completion.getReflection().getHolding().getId());
		PracticeMarketObservation observation = currentRunObservations(userId, resolved).stream()
			.filter(candidate -> candidate.getEvidenceType() != null)
			.findFirst()
			.orElse(null);
		LocalDateTime saleDeadlineAt = attemptSaleDeadlineAt(attempt, resolved.riskSnapshot());
		PracticeEvidenceResponse evidence = attemptEvidence(
			resolved, observation, saleDeadlineAt, completion.getReflection());
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(3, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(4, STATUS_COMPLETED, false, evidence));
		return new InvestmentPracticeResponse(
			tutorialKey,
			STATUS_COMPLETED,
			null,
			steps,
			completion.getCompletedAt(),
			TUTORIAL_COMPLETION_REWARD_AMOUNT,
			PracticeAttemptResponse.from(attempt, resolved.riskSnapshot(), exitPresetLocked(attempt)));
	}

	private List<PracticeMarketObservation> currentRunObservations(
		Long userId, ResolvedPracticeAttemptEvidenceDto resolved) {
		return practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(userId, resolved.holdingId())
			.stream()
			.filter(observation -> !observation.getObservedAt()
				.isBefore(resolved.observationBaseline().getCreatedAt()))
			.toList();
	}

	private boolean exitPresetLocked(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return false;
		}
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber()).signum() > 0;
	}

	private LocalDateTime attemptSaleDeadlineAt(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
		return attempt.usesScenarioScript()
			? null
			: snapshot.getBuyTrade().getExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES);
	}

	private PracticeEvidenceResponse attemptEvidence(
		ResolvedPracticeAttemptEvidenceDto resolved,
		PracticeMarketObservation observation,
		LocalDateTime saleDeadlineAt,
		PracticeMarketReflection reflection) {
		return new PracticeEvidenceResponse(
			null,
			null,
			null,
			null,
			resolved.riskSnapshot().getBuyTrade().getId(),
			resolved.riskSnapshot().getBuyTrade().getExecutedAt(),
			resolved.holdingId(),
			resolved.riskSnapshot().getStopLossPrice(),
			resolved.riskSnapshot().getTakeProfitPrice(),
			observation == null ? null : observation.getId(),
			observation == null ? null : observation.getObservedAt(),
			observation == null || observation.getEvidenceType() == null
				? null
				: observation.getEvidenceType().name(),
			reflection == null ? null : reflection.getId(),
			reflection == null ? null : reflection.getCreatedAt(),
			resolved.sellTrade() == null ? null : resolved.sellTrade().getId(),
			resolved.sellTrade() == null ? null : resolved.sellTrade().getExecutedAt(),
			saleDeadlineAt,
			resolved.buyQuantity(),
			resolved.sellQuantity(),
			resolved.remainingQuantity(),
			PracticeTradeResultCalculator.calculate(
				resolved.averageBuyPrice(),
				resolved.averageSellPrice(),
				resolved.realizedPnl(),
				resolved.soldBuyBasis(),
				resolved.riskSnapshot().getStopLossPrice(),
				resolved.riskSnapshot().getTakeProfitPrice(),
				resolved.sellCause()));
	}

	private InvestmentPracticeResponse buildCompletedResponse(
		Long userId, String tutorialKey, PracticeCompletion completion) {
		PracticeMarketReflection reflection = completion.getReflection();
		Holding holding = reflection.getHolding();
		boolean sampleInstrument = holding.getInstrument().isTutorialSample();

		Optional<ResolvedPracticeChainDto> chain = chainResolutionService
			.resolveForInstrument(userId, tutorialKey, holding.getInstrument().getId())
			.filter(resolved -> resolved.holdingId().equals(holding.getId()));

		List<PracticeMarketObservation> observations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(userId, holding.getId());
		Optional<PracticeMarketObservation> qualifyingObservation = observations.stream()
			.filter(observation -> observation.getEvidenceType() != null)
			.findFirst();

		PracticeEvidenceResponse evidence = new PracticeEvidenceResponse(
			chain.map(ResolvedPracticeChainDto::favoriteId).orElse(null),
			chain.map(ResolvedPracticeChainDto::favoriteCreatedAt).orElse(null),
			chain.map(ResolvedPracticeChainDto::intentionId).orElse(null),
			chain.map(ResolvedPracticeChainDto::intentionCreatedAt).orElse(null),
			chain.map(ResolvedPracticeChainDto::buyTradeId).orElse(null),
			chain.map(ResolvedPracticeChainDto::buyTradeExecutedAt).orElse(null),
			holding.getId(),
			null,
			null,
			qualifyingObservation.map(PracticeMarketObservation::getId).orElse(null),
			qualifyingObservation.map(PracticeMarketObservation::getObservedAt).orElse(null),
			qualifyingObservation.map(observation -> observation.getEvidenceType().name()).orElse(null),
			reflection.getId(),
			reflection.getCreatedAt(),
			null,
			null,
			null,
			null,
			null,
			null,
			null);

		if (!sampleInstrument) {
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_COMPLETED, false, evidence),
				new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
				new PracticeStepResponse(3, STATUS_COMPLETED, false, evidence));
			return new InvestmentPracticeResponse(
				tutorialKey, STATUS_COMPLETED, null, steps, completion.getCompletedAt(),
				TUTORIAL_COMPLETION_REWARD_AMOUNT, null);
		}

		PracticeEvidenceResponse stepFourEvidence = new PracticeEvidenceResponse(
			evidence.favoriteId(), evidence.favoriteCreatedAt(), evidence.intentionId(), evidence.intentionCreatedAt(),
			evidence.buyTradeId(), evidence.buyTradeExecutedAt(), evidence.holdingId(),
			evidence.referenceStopLossPrice(), evidence.referenceTakeProfitPrice(),
			evidence.observationId(), evidence.observationObservedAt(), evidence.evidenceType(),
			evidence.reflectionId(), evidence.reflectionCreatedAt(),
			chain.map(ResolvedPracticeChainDto::sellTradeId).orElse(null),
			chain.map(ResolvedPracticeChainDto::sellTradeExecutedAt).orElse(null),
			evidence.buyTradeExecutedAt() == null
				? null
				: evidence.buyTradeExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES),
			null,
			null,
			null,
			null);

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(3, STATUS_COMPLETED, false, evidence),
			new PracticeStepResponse(4, STATUS_COMPLETED, false, stepFourEvidence));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_COMPLETED, null, steps, completion.getCompletedAt(),
			TUTORIAL_COMPLETION_REWARD_AMOUNT, null);
	}

	private InvestmentPracticeResponse buildChainResponse(
		Long userId, String tutorialKey, ResolvedPracticeChainDto chain) {
		Optional<ReferencePriceLines> referenceLines = referencePriceCalculator.calculate(chain);
		PracticeEvidenceResponse chainEvidence = new PracticeEvidenceResponse(
			chain.favoriteId(),
			chain.favoriteCreatedAt(),
			chain.intentionId(),
			chain.intentionCreatedAt(),
			chain.buyTradeId(),
			chain.buyTradeExecutedAt(),
			chain.holdingId(),
			referenceLines.map(ReferencePriceLines::referenceStopLossPrice).orElse(null),
			referenceLines.map(ReferencePriceLines::referenceTakeProfitPrice).orElse(null),
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);

		List<PracticeMarketObservation> observations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(userId, chain.holdingId());
		Optional<PracticeMarketObservation> qualifyingObservation = observations.stream()
			.filter(observation -> observation.getEvidenceType() != null)
			.findFirst();

		PracticeEvidenceResponse stepThreeEvidence = qualifyingObservation
			.map(observation -> new PracticeEvidenceResponse(
				chainEvidence.favoriteId(),
				chainEvidence.favoriteCreatedAt(),
				chainEvidence.intentionId(),
				chainEvidence.intentionCreatedAt(),
				chainEvidence.buyTradeId(),
				chainEvidence.buyTradeExecutedAt(),
				chainEvidence.holdingId(),
				chainEvidence.referenceStopLossPrice(),
				chainEvidence.referenceTakeProfitPrice(),
				observation.getId(),
				observation.getObservedAt(),
				observation.getEvidenceType().name(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null))
			.orElse(chainEvidence);

		PracticeEvidenceResponse favoriteEvidence = PracticeEvidenceResponse
			.favoriteOnly(chain.favoriteId(), chain.favoriteCreatedAt());

		if (!chain.instrumentIsTutorialSample()) {
			List<PracticeStepResponse> steps = List.of(
				new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
				new PracticeStepResponse(2, STATUS_COMPLETED, false, chainEvidence),
				new PracticeStepResponse(3, STATUS_IN_PROGRESS, false, stepThreeEvidence));
			return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 3, steps, null, null, null);
		}

		LocalDateTime saleDeadlineAt = chain.buyTradeExecutedAt() == null
			? null
			: chain.buyTradeExecutedAt().plusMinutes(SALE_DEADLINE_MINUTES);
		String stepFourStatus = resolveStepFourStatus(chain, saleDeadlineAt);

		PracticeEvidenceResponse stepFourEvidence = new PracticeEvidenceResponse(
			chainEvidence.favoriteId(),
			chainEvidence.favoriteCreatedAt(),
			chainEvidence.intentionId(),
			chainEvidence.intentionCreatedAt(),
			chainEvidence.buyTradeId(),
			chainEvidence.buyTradeExecutedAt(),
			chainEvidence.holdingId(),
			chainEvidence.referenceStopLossPrice(),
			chainEvidence.referenceTakeProfitPrice(),
			null,
			null,
			null,
			null,
			null,
			chain.sellTradeId(),
			chain.sellTradeExecutedAt(),
			saleDeadlineAt,
			null,
			null,
			null,
			null);

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
			new PracticeStepResponse(2, STATUS_COMPLETED, false, chainEvidence),
			new PracticeStepResponse(3, STATUS_IN_PROGRESS, false, stepThreeEvidence),
			new PracticeStepResponse(4, stepFourStatus, false, stepFourEvidence));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 4, steps, null, null, null);
	}

	private String resolveStepFourStatus(ResolvedPracticeChainDto chain, LocalDateTime saleDeadlineAt) {
		if (chain.sellTradeId() != null) {
			return isWithinSaleDeadline(chain.sellTradeExecutedAt(), saleDeadlineAt)
				? STATUS_IN_PROGRESS
				: STATUS_EXPIRED;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return isWithinSaleDeadline(now, saleDeadlineAt) ? STATUS_AWAITING_SALE : STATUS_EXPIRED;
	}

	private boolean isWithinSaleDeadline(LocalDateTime at, LocalDateTime saleDeadlineAt) {
		return saleDeadlineAt == null || !at.isAfter(saleDeadlineAt);
	}

	private InvestmentPracticeResponse buildFavoriteOnlyResponse(
		String tutorialKey, List<FavoriteResponse> marketFavorites) {
		FavoriteResponse earliestFavorite = marketFavorites.stream()
			.min(Comparator.comparing(FavoriteResponse::createdAt))
			.orElseThrow();
		PracticeEvidenceResponse favoriteEvidence = PracticeEvidenceResponse
			.favoriteOnly(earliestFavorite.favoriteId(), earliestFavorite.createdAt());

		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_COMPLETED, false, favoriteEvidence),
			new PracticeStepResponse(2, STATUS_IN_PROGRESS, false, favoriteEvidence),
			new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_IN_PROGRESS, 2, steps, null, null, null);
	}

	private InvestmentPracticeResponse buildNotStartedResponse(String tutorialKey) {
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, STATUS_NOT_STARTED, false, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(2, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(3, STATUS_NOT_STARTED, true, PracticeEvidenceResponse.empty()));
		return new InvestmentPracticeResponse(tutorialKey, STATUS_NOT_STARTED, 1, steps, null, null, null);
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}
}
