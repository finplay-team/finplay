package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class EvidenceJudgmentService {

	private static final int MIN_OBSERVATION_COUNT_FOR_TIMED_REPETITION = 3;
	private static final Duration MIN_TIMED_REPETITION_SPAN = Duration.ofMinutes(2);

	public ObservationEvidenceJudgment judgeObservationEvidence(
		BigDecimal entryPrice,
		BigDecimal referenceStopLossPrice,
		BigDecimal referenceTakeProfitPrice,
		BigDecimal currentPrice,
		List<PracticeMarketObservation> existingObservations,
		LocalDateTime newObservedAt) {
		BoundaryEvidenceResult boundaryEvidence = judgeBoundaryEvidence(
			entryPrice, referenceStopLossPrice, referenceTakeProfitPrice, currentPrice);
		if (boundaryEvidence.evidenceType() != null) {
			return new ObservationEvidenceJudgment(
				boundaryEvidence.closerToBoundary(), boundaryEvidence.closerBoundary(),
				boundaryEvidence.evidenceType());
		}

		Optional<PracticeEvidenceType> timedRepetition = judgeTimedRepetition(existingObservations, newObservedAt);
		if (timedRepetition.isPresent()) {
			return new ObservationEvidenceJudgment(false, null, timedRepetition.get());
		}

		return new ObservationEvidenceJudgment(false, null, null);
	}

	public BoundaryEvidenceResult judgeBoundaryEvidence(
		BigDecimal entryPrice, BigDecimal referenceStopLossPrice, BigDecimal referenceTakeProfitPrice,
		BigDecimal currentPrice) {
		BigDecimal baselineStopLossDistance = entryPrice.subtract(referenceStopLossPrice).abs();
		BigDecimal baselineTakeProfitDistance = referenceTakeProfitPrice.subtract(entryPrice).abs();
		BigDecimal baselineDistance = baselineStopLossDistance.min(baselineTakeProfitDistance);

		BigDecimal currentStopLossDistance = currentPrice.subtract(referenceStopLossPrice).abs();
		BigDecimal currentTakeProfitDistance = referenceTakeProfitPrice.subtract(currentPrice).abs();
		boolean nearerToStopLoss = currentStopLossDistance.compareTo(currentTakeProfitDistance) <= 0;
		BigDecimal currentDistance = nearerToStopLoss ? currentStopLossDistance : currentTakeProfitDistance;

		if (currentDistance.compareTo(baselineDistance) < 0) {
			PracticeBoundary closerBoundary = nearerToStopLoss ? PracticeBoundary.STOP_LOSS
				: PracticeBoundary.TAKE_PROFIT;
			return new BoundaryEvidenceResult(true, closerBoundary, PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		}
		return new BoundaryEvidenceResult(false, null, null);
	}

	public Optional<PracticeEvidenceType> judgeTimedRepetition(
		List<PracticeMarketObservation> existingObservations, LocalDateTime newObservedAt) {
		if (existingObservations.size() + 1 < MIN_OBSERVATION_COUNT_FOR_TIMED_REPETITION) {
			return Optional.empty();
		}

		LocalDateTime earliestObservedAt = existingObservations.stream()
			.map(PracticeMarketObservation::getObservedAt)
			.min(Comparator.naturalOrder())
			.orElse(newObservedAt);
		LocalDateTime latestObservedAt = existingObservations.stream()
			.map(PracticeMarketObservation::getObservedAt)
			.max(Comparator.naturalOrder())
			.map(existingLatest -> existingLatest.isAfter(newObservedAt) ? existingLatest : newObservedAt)
			.orElse(newObservedAt);

		if (!Duration.between(earliestObservedAt, latestObservedAt).minus(MIN_TIMED_REPETITION_SPAN).isNegative()) {
			return Optional.of(PracticeEvidenceType.TIMED_REPETITION);
		}
		return Optional.empty();
	}
}
