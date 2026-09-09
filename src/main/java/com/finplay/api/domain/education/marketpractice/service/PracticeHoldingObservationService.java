package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceObservationService;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeHoldingObservationService {

	private final HoldingService holdingService;
	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService;
	private final MarketPracticeChainResolutionService chainResolutionService;
	private final PriceQueryService priceQueryService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticePriceObservationService practicePriceObservationService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final EvidenceJudgmentService evidenceJudgmentService;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final Clock clock;

	@Transactional
	public PracticeHoldingObservationResponse createObservation(
		Long userId, PracticeHoldingObservationCreateRequest request) {
		Holding holding = holdingService.findHoldingForOwner(userId, request.holdingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		Optional<PracticeAttempt> attempt = holding.getInstrument().isTutorialSample()
			? practiceAttemptRepository.findByUserIdAndMarket(userId, holding.getInstrument().getMarket())
			: Optional.empty();
		if (attempt.isPresent()) {
			return createAttemptObservation(userId, holding, attempt.get());
		}

		String tutorialKey = resolveTutorialKey(holding.getInstrument().getMarket());
		ResolvedPracticeChainDto chain = chainResolutionService
			.resolveForInstrument(userId, tutorialKey, holding.getInstrument().getId())
			.filter(resolved -> resolved.holdingId().equals(holding.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		ReferencePriceLines referenceLines = referencePriceCalculator.calculate(chain)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		LocalDateTime observedAt = LocalDateTime.now(clock);
		BigDecimal observedPrice = practicePriceObservationService
			.findObservationPrice(userId, chain.buyTradeId(), holding.getInstrument().getId())
			.orElseGet(() -> priceQueryService.getPrice(holding.getInstrument().getId()).price());

		List<PracticeMarketObservation> existingObservations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId());
		ObservationEvidenceJudgment judgment = evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(),
			referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(),
			observedPrice,
			existingObservations,
			observedAt);

		PracticeMarketObservation observation = PracticeMarketObservation.create(
			userId,
			holding,
			holding.getInstrument().getId(),
			observedPrice,
			judgment.closerToBoundary(),
			judgment.closerBoundary(),
			judgment.evidenceType(),
			observedAt);

		return PracticeHoldingObservationResponse.from(practiceMarketObservationRepository.save(observation));
	}

	private PracticeHoldingObservationResponse createAttemptObservation(
		Long userId, Holding holding, PracticeAttempt attempt) {
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		ResolvedPracticeAttemptEvidenceDto evidence = practiceAttemptEvidenceService
			.requireCurrentRun(attempt, userId, holding.getId());
		LocalDateTime observedAt = LocalDateTime.now(clock);
		BigDecimal observedPrice = canonicalPriceService
			.canonicalPriceForMutation(userId, holding.getInstrument(), observedAt);

		List<PracticeMarketObservation> existingObservations = practiceMarketObservationRepository
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holding.getId())
			.stream()
			.filter(observation -> !observation.getObservedAt()
				.isBefore(evidence.observationBaseline().getCreatedAt()))
			.toList();
		ObservationEvidenceJudgment judgment = evidenceJudgmentService.judgeObservationEvidence(
			evidence.riskSnapshot().getEntryPrice(),
			evidence.riskSnapshot().getStopLossPrice(),
			evidence.riskSnapshot().getTakeProfitPrice(),
			observedPrice,
			existingObservations,
			observedAt);
		PracticeMarketObservation observation = PracticeMarketObservation.create(
			userId,
			holding,
			holding.getInstrument().getId(),
			observedPrice,
			judgment.closerToBoundary(),
			judgment.closerBoundary(),
			judgment.evidenceType(),
			observedAt);
		return PracticeHoldingObservationResponse.from(practiceMarketObservationRepository.save(observation));
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> PracticeIntentionService.TUTORIAL_KEY;
			case CRYPTO -> PracticeIntentionService.COIN_TUTORIAL_KEY;
		};
	}
}
