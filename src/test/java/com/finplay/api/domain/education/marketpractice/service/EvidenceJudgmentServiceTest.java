package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.portfolio.entity.Holding;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceJudgmentServiceTest {

	private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100");
	private static final BigDecimal REFERENCE_STOP_LOSS = new BigDecimal("90");
	private static final BigDecimal REFERENCE_TAKE_PROFIT = new BigDecimal("110");

	private final EvidenceJudgmentService service = new EvidenceJudgmentService();

	private PracticeMarketObservation observationAt(LocalDateTime observedAt) {
		return PracticeMarketObservation.create(
			1L, mock(Holding.class), 100L, new BigDecimal("95"), false, null, null, observedAt);
	}

	@Test
	void judgeBoundaryEvidenceReturnsCloserToStopLossWhenCurrentPriceMovedNearerToStopLoss() {
		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, new BigDecimal("95"));

		assertThat(result.closerToBoundary()).isTrue();
		assertThat(result.closerBoundary()).isEqualTo(PracticeBoundary.STOP_LOSS);
		assertThat(result.evidenceType()).isEqualTo(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
	}

	@Test
	void judgeBoundaryEvidenceReturnsCloserToTakeProfitWhenCurrentPriceMovedNearerToTakeProfit() {
		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, new BigDecimal("108"));

		assertThat(result.closerToBoundary()).isTrue();
		assertThat(result.closerBoundary()).isEqualTo(PracticeBoundary.TAKE_PROFIT);
		assertThat(result.evidenceType()).isEqualTo(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
	}

	@Test
	void judgeBoundaryEvidenceReturnsNotCloserWhenCurrentPriceMovedAwayFromBothBoundaries() {
		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, new BigDecimal("70"));

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.closerBoundary()).isNull();
		assertThat(result.evidenceType()).isNull();
	}

	@Test
	void judgeBoundaryEvidenceReturnsNotCloserWhenCurrentDistanceEqualsBaselineDistance() {
		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.evidenceType()).isNull();
	}

	@Test
	void judgeBoundaryEvidencePrefersStopLossWhenCurrentDistancesToBothBoundariesAreEqual() {
		BigDecimal midpoint = REFERENCE_STOP_LOSS.add(REFERENCE_TAKE_PROFIT).divide(new BigDecimal("2"));

		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, midpoint);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.evidenceType()).isNull();
	}

	@Test
	void judgeTimedRepetitionReturnsEmptyWhenNoExistingObservations() {
		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(List.of(), T0.plusMinutes(5));

		assertThat(result).isEmpty();
	}

	@Test
	void judgeTimedRepetitionReturnsEmptyWhenOnlyOneExistingObservation() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0));

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, T0.plusMinutes(5));

		assertThat(result).isEmpty();
	}

	@Test
	void judgeTimedRepetitionSucceedsWhenTwoExistingObservationsPlusThisAttemptSpanTwoMinutes() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusSeconds(10)));

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, T0.plusMinutes(5));

		assertThat(result).contains(PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeTimedRepetitionReturnsEmptyWhenThreeObservationsSpanLessThanTwoMinutes() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusSeconds(30)));
		LocalDateTime newObservedAt = T0.plusSeconds(59);

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, newObservedAt);

		assertThat(result).isEmpty();
	}

	@Test
	void judgeTimedRepetitionReturnsTimedRepetitionWhenThreeObservationsSpanExactlyTwoMinutes() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusSeconds(1)));
		LocalDateTime newObservedAt = T0.plusMinutes(2);

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, newObservedAt);

		assertThat(result).contains(PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeTimedRepetitionReturnsTimedRepetitionWhenMoreThanThreeObservationsAlreadySpanTwoMinutes() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusSeconds(30)),
			observationAt(T0.plusMinutes(3)));
		LocalDateTime newObservedAt = T0.plusMinutes(3).plusSeconds(30);

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, newObservedAt);

		assertThat(result).contains(PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeTimedRepetitionUsesLatestExistingObservationWhenItIsAfterNewObservedAt() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusMinutes(5)));
		LocalDateTime newObservedAt = T0.plusSeconds(1);

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, newObservedAt);

		assertThat(result).contains(PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeObservationEvidenceReturnsBoundaryEvidenceWhenBothAAndBWouldBeSatisfied() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusMinutes(3)));
		LocalDateTime newObservedAt = T0.plusMinutes(3).plusSeconds(30);

		ObservationEvidenceJudgment result = service.judgeObservationEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, new BigDecimal("95"), existing, newObservedAt);

		assertThat(result.closerToBoundary()).isTrue();
		assertThat(result.closerBoundary()).isEqualTo(PracticeBoundary.STOP_LOSS);
		assertThat(result.evidenceType()).isEqualTo(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
	}

	@Test
	void judgeObservationEvidenceFallsBackToTimedRepetitionWhenBoundaryEvidenceNotSatisfied() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusSeconds(1)));
		LocalDateTime newObservedAt = T0.plusMinutes(2);

		ObservationEvidenceJudgment result = service.judgeObservationEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE, existing, newObservedAt);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.closerBoundary()).isNull();
		assertThat(result.evidenceType()).isEqualTo(PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeObservationEvidenceReturnsNullEvidenceTypeWhenNeitherAnorBSatisfied() {
		List<PracticeMarketObservation> existing = List.of(observationAt(T0));
		LocalDateTime newObservedAt = T0.plusSeconds(10);

		ObservationEvidenceJudgment result = service.judgeObservationEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE, existing, newObservedAt);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.closerBoundary()).isNull();
		assertThat(result.evidenceType()).isNull();
	}

	@Test
	void practiceEvidenceTypeEnumHasNoFinalEventValueAndOnlyDefinesAAndB() {
		assertThat(PracticeEvidenceType.values())
			.containsExactlyInAnyOrder(PracticeEvidenceType.CLOSER_TO_BOUNDARY, PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeObservationEvidenceNeverReturnsAnEvidenceTypeOtherThanClosestToBoundaryOrTimedRepetition() {
		List<PracticeMarketObservation> existing = List.of();
		LocalDateTime newObservedAt = T0;

		ObservationEvidenceJudgment result = service.judgeObservationEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE, existing, newObservedAt);

		assertThat(result.evidenceType()).isIn((Object)null, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			PracticeEvidenceType.TIMED_REPETITION);
	}
}
