// StockReplaySession의 preparing/ready/failed 정적 팩토리가 상태별 nullable 규칙(spec.md 비즈니스 규칙)을 강제하는지 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.market.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StockReplaySessionTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 28);
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 27);
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 28, 8, 0, 0);
	private static final LocalDateTime RESOLVED_AT = LocalDateTime.of(2026, 7, 28, 8, 55, 0);

	// preparing()은 resolvedAt·failureReason을 파라미터로 받지 않는다 — "PREPARING+resolved_at 존재",
	// "PREPARING+failure_reason 존재"는 팩토리 시그니처상 호출 자체가 불가능해 구조적으로 거부된다.
	// 대신 어떤 sourceTradingDate로 호출해도 두 필드가 항상 NULL로 고정되는지를 검증한다.
	@Test
	void preparingAllowsNullSourceTradingDateAndAlwaysLeavesResolvedAtAndFailureReasonNull() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.PREPARING);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getResolvedAt()).isNull();
		assertThat(session.getFailureReason()).isNull();
	}

	@Test
	void preparingAllowsPresentSourceTradingDateWhileValidatingCandidateAndAlwaysLeavesResolvedAtAndFailureReasonNull() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, SOURCE_TRADING_DATE, CREATED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.PREPARING);
		assertThat(session.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(session.getResolvedAt()).isNull();
		assertThat(session.getFailureReason()).isNull();
	}

	@Test
	void readyRejectsNullSourceTradingDate() {
		assertThatThrownBy(() -> StockReplaySession.ready(SERVICE_DATE, null, RESOLVED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void readyRejectsNullResolvedAt() {
		assertThatThrownBy(() -> StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ready()도 failureReason 파라미터가 없다 — "READY+failure_reason 존재"는 호출 자체가 불가능하다.
	// 필수값(sourceTradingDate·resolvedAt)을 채운 정상 호출에서 failureReason이 항상 NULL임을 확인한다.
	@Test
	void readyWithRequiredFieldsSucceedsAndLeavesFailureReasonNull() {
		StockReplaySession session = StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT,
			CREATED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isNull();
	}

	@Test
	void failedRejectsNullResolvedAt() {
		assertThatThrownBy(
			() -> StockReplaySession.failed(SERVICE_DATE, SOURCE_TRADING_DATE, null, "파싱 실패", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedRejectsNullFailureReason() {
		assertThatThrownBy(
			() -> StockReplaySession.failed(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedRejectsBlankFailureReason() {
		assertThatThrownBy(
			() -> StockReplaySession.failed(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, "   ", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failedAllowsNullSourceTradingDateWhenNoCandidateWasEverFound() {
		StockReplaySession session = StockReplaySession.failed(SERVICE_DATE, null, RESOLVED_AT, "데이터 없음", CREATED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isEqualTo("데이터 없음");
	}

	@Test
	void failedAllowsPresentSourceTradingDateWhenAFailedCandidateWasIdentified() {
		StockReplaySession session = StockReplaySession.failed(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, "검증 실패",
			CREATED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isEqualTo("검증 실패");
	}

	// --- resolveReady/resolveFailed (StockReplaySessionScheduler 전용 인스턴스 메서드) ---
	// 정적 팩토리 ready()/failed()와 같은 nullable 규칙을 PREPARING 세션의 상태 전환에서도 강제하는지 검증한다.

	@Test
	void resolveReadyTransitionsPreparingSessionToReadyWithGivenSourceTradingDateAndResolvedAt() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		session.resolveReady(SOURCE_TRADING_DATE, RESOLVED_AT);

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(session.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isNull();
	}

	@Test
	void resolveReadyRejectsNullSourceTradingDate() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThatThrownBy(() -> session.resolveReady(null, RESOLVED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resolveReadyRejectsNullResolvedAt() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThatThrownBy(() -> session.resolveReady(SOURCE_TRADING_DATE, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resolveReadyRejectsWhenSessionIsAlreadyReady() {
		StockReplaySession session = StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT,
			CREATED_AT);

		assertThatThrownBy(() -> session.resolveReady(SOURCE_TRADING_DATE, RESOLVED_AT))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resolveReadyRejectsWhenSessionIsAlreadyFailed() {
		StockReplaySession session = StockReplaySession.failed(SERVICE_DATE, null, RESOLVED_AT, "데이터 없음", CREATED_AT);

		assertThatThrownBy(() -> session.resolveReady(SOURCE_TRADING_DATE, RESOLVED_AT))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resolveFailedTransitionsPreparingSessionToFailedAllowingNullSourceTradingDateWhenNoCandidateWasFound() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		session.resolveFailed(null, RESOLVED_AT, "검증 완료된 거래일 데이터를 찾지 못했습니다.");

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isNull();
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isEqualTo("검증 완료된 거래일 데이터를 찾지 못했습니다.");
	}

	@Test
	void resolveFailedTransitionsPreparingSessionToFailedAllowingPresentSourceTradingDateWhenAFailedCandidateWasIdentified() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, SOURCE_TRADING_DATE, CREATED_AT);

		session.resolveFailed(SOURCE_TRADING_DATE, RESOLVED_AT, "검증 실패");

		assertThat(session.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
		assertThat(session.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(session.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(session.getFailureReason()).isEqualTo("검증 실패");
	}

	@Test
	void resolveFailedRejectsNullResolvedAt() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThatThrownBy(() -> session.resolveFailed(null, null, "데이터 없음"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resolveFailedRejectsNullFailureReason() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThatThrownBy(() -> session.resolveFailed(null, RESOLVED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resolveFailedRejectsBlankFailureReason() {
		StockReplaySession session = StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT);

		assertThatThrownBy(() -> session.resolveFailed(null, RESOLVED_AT, "   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void resolveFailedRejectsWhenSessionIsAlreadyReady() {
		StockReplaySession session = StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT,
			CREATED_AT);

		assertThatThrownBy(() -> session.resolveFailed(null, RESOLVED_AT, "데이터 없음"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resolveFailedRejectsWhenSessionIsAlreadyFailed() {
		StockReplaySession session = StockReplaySession.failed(SERVICE_DATE, null, RESOLVED_AT, "데이터 없음", CREATED_AT);

		assertThatThrownBy(() -> session.resolveFailed(null, RESOLVED_AT, "데이터 없음"))
			.isInstanceOf(IllegalStateException.class);
	}
}
