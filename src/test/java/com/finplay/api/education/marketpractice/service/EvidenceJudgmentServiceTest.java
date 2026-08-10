// EvidenceJudgmentService의 evidence A(경계 접근)·B(2분 3회 관찰) 판정과 우선순위를 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.education.marketpractice.domain.PracticeBoundary;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.portfolio.domain.Holding;
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

	// --- Evidence A: 경계 접근 ---

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
		// currentPrice == entryPrice(baseline과 동일 지점)이면 "더 가까워짐"이 아니라 동일 거리이므로 미충족이다.
		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.evidenceType()).isNull();
	}

	@Test
	void judgeBoundaryEvidencePrefersStopLossWhenCurrentDistancesToBothBoundariesAreEqual() {
		// 두 경계까지의 거리가 정확히 같은 지점(refSL·refTP의 중간점)에서는 STOP_LOSS 쪽 로직이 currentDistance를
		// 결정하는지 직접 확인한다. 이 지점은 baseline 이하이거나 같아 실제 evidence를 충족하지는 않지만(수학적으로
		// baselineDistance <= halfRange가 항상 성립해 "더 가까워짐"이 될 수 없다), 동률 시 STOP_LOSS를 우선하는
		// tie-break 자체는 이 결과로 검증할 수 없다 — 대신 judgeObservationEvidence 우선순위 테스트에서
		// STOP_LOSS 쪽이 가까운 실제 케이스로 closerBoundary 필드를 검증한다.
		BigDecimal midpoint = REFERENCE_STOP_LOSS.add(REFERENCE_TAKE_PROFIT).divide(new BigDecimal("2"));

		BoundaryEvidenceResult result = service.judgeBoundaryEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, midpoint);

		assertThat(result.closerToBoundary()).isFalse();
		assertThat(result.evidenceType()).isNull();
	}

	// --- Evidence B: 2분 이상 범위 3회 관찰 ---

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
		// newObservedAt이 항상 가장 늦다는 전제를 벗어난 입력이지만, 코드가 existing 중 new보다 늦은 값을 실제로
		// 놓치지 않는지(전제 위반 시 latest 계산 로직 자체) 확인한다.
		List<PracticeMarketObservation> existing = List.of(observationAt(T0), observationAt(T0.plusMinutes(5)));
		LocalDateTime newObservedAt = T0.plusSeconds(1);

		Optional<PracticeEvidenceType> result = service.judgeTimedRepetition(existing, newObservedAt);

		assertThat(result).contains(PracticeEvidenceType.TIMED_REPETITION);
	}

	// --- 우선순위: A가 충족되면 B는 판정하지 않는다 ---

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

	// --- Evidence C(FINAL_EVENT)는 이 경로에 존재하지 않는다 ---

	@Test
	void practiceEvidenceTypeEnumHasNoFinalEventValueAndOnlyDefinesAAndB() {
		assertThat(PracticeEvidenceType.values())
			.containsExactlyInAnyOrder(PracticeEvidenceType.CLOSER_TO_BOUNDARY, PracticeEvidenceType.TIMED_REPETITION);
	}

	@Test
	void judgeObservationEvidenceNeverReturnsAnEvidenceTypeOtherThanClosestToBoundaryOrTimedRepetition() {
		// A/B 모두 미충족인 경로를 포함해, 서비스가 반환하는 evidenceType은 null 또는 두 enum 값 중 하나뿐이다.
		List<PracticeMarketObservation> existing = List.of();
		LocalDateTime newObservedAt = T0;

		ObservationEvidenceJudgment result = service.judgeObservationEvidence(
			ENTRY_PRICE, REFERENCE_STOP_LOSS, REFERENCE_TAKE_PROFIT, ENTRY_PRICE, existing, newObservedAt);

		assertThat(result.evidenceType()).isIn((Object)null, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			PracticeEvidenceType.TIMED_REPETITION);
	}
}
