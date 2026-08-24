// 실습 3단계를 여는 evidence A(경계 접근)·B(2분 이상 3회 시간 분산 관찰) 판정만 수행하는 순수 서비스
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

/**
 * ai/specs/026-market-order-practice-tutorial plan.md "Evidence 계산 규칙" 절의 A·B 판정을 구현한다. 이
 * 서비스는 어떤 저장도 하지 않는다 — 관찰 1건을 실제로 insert하는 트랜잭션은 이 spec의 다음 작업 항목
 * (tasks.md 3번, Controller)이 만든다. 여기서는 "주어진 참조 가격선·현재가·기존 관찰 목록 + 이번 관찰
 * 시도"만 받아 그 관찰이 A 또는 B를 충족하는지만 계산해 반환한다.
 */
@Service
public class EvidenceJudgmentService {

	private static final int MIN_OBSERVATION_COUNT_FOR_TIMED_REPETITION = 3;
	private static final Duration MIN_TIMED_REPETITION_SPAN = Duration.ofMinutes(2);

	/**
	 * A를 우선 판정하고 A가 충족되지 않으면 B를 판정한다({@code evidence_type} 컬럼이 관찰 1건당 하나만 담을 수
	 * 있으므로 plan.md의 절 순서(A 다음 B)를 그대로 우선순위로 쓴다). 둘 다 미충족이면 {@code evidenceType}이
	 * {@code null}인 결과를 반환한다.
	 */
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

	/**
	 * Evidence A: {@code baselineDistance}(매수가 기준 두 경계까지 거리 중 짧은 쪽)보다 {@code currentDistance}
	 * (현재가 기준 두 경계까지 거리 중 짧은 쪽)가 더 짧아졌으면 경계에 가까워진 것으로 판정한다. 더 가까운 쪽
	 * 경계를 {@code closerBoundary}로 그대로 노출한다.
	 *
	 * <p><b>동률 tie-break</b>: 두 거리가 정확히 같으면 {@link PracticeBoundary#STOP_LOSS}를 우선한다
	 * (`ai/specs/026-market-order-practice-tutorial/plan.md` "Evidence A" 절, PR #298 리뷰에서 명시
	 * 확정). {@code 019}의 {@code stopLossPrice < entryPrice < takeProfitPrice} 불변조건이 유지되는 한
	 * 동률 지점의 거리는 항상 {@code baselineDistance} 이상이라 이 분기는 {@code closerToBoundary=true}로
	 * 이어지지 못한다(현재 도달 불가능) — 그래도 그 불변조건이 깨지는 입력이 들어올 경우를 위해 임의 방치
	 * 대신 명시적으로 정해 둔다.
	 */
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

	/**
	 * Evidence B: 기존에 저장된 관찰 목록에 이번 관찰 시도({@code newObservedAt})를 더한 전체 집합이 3회 이상이고
	 * 그중 가장 이른 시각과 가장 늦은 시각의 차이가 2분 이상이면 충족한다. 이번 관찰 시도는 항상 가장 늦게
	 * 기록되는 관찰이라고 전제한다(호출 시점의 현재 시각) — plan.md "그 관찰(가장 늦은 것)"이 이 시도를
	 * 가리킨다.
	 */
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
