// 2단계 대본을 마친 실행을 같은 run 안에서 3단계 대본으로 전환하는 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 049 ORDERBASICS-018~021 — 2단계(주문 방법 학습) 대본을 마친 실행을 같은 run 안에서 3단계(041 이야기)
 * 대본으로 갈아끼운다.
 *
 * <p><b>전환은 재시작이 아니다.</b> run을 올리지 않고 튜토리얼 계좌·{@code exitPreset}을 건드리지 않는다
 * — 재시작은 {@code tutorialStageProgress} 세 값을 전부 되돌려, 3단계로 넘어갈 자격을 확인한 직후 그
 * 자격을 지운다(plan §3).
 *
 * <p>잠금 순서는 기존 경로와 같다 — attempt를 먼저 비관 잠금한다.
 */
@Service
@RequiredArgsConstructor
public class PracticeAttemptScriptAdvanceService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService;
	private final TradeService tradeService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final TutorialAccountService tutorialAccountService;
	private final Clock clock;

	@Transactional
	public PracticeAttemptResponse advanceScript(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));

		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		// 대본을 쓰지 않는 실행(생성기 버전 1)이거나 이미 3단계 대본이면 전환할 것이 없다.
		if (!attempt.usesScenarioScript() || attempt.scenarioScriptId() == TutorialScenarioScriptId.CRYPTO_STORY_V1) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
		PracticeStageProgressResponse progress = practiceStageProgressCalculationService.calculate(attempt);
		if (!progress.marketBuySellCompleted() || !progress.limitBuySellCompleted()) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
		// 보유 중 전환 금지(ORDERBASICS-020) — 진입가 100,000원짜리 보유가 1만원대 3단계 곡선 위에
		// 그대로 놓이는 것을 막는다.
		if (tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber()).signum() > 0) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}

		practiceOrderSettlementService.cancelCurrentRunExitPlans(userId, attempt.getId(), attempt.getRunNumber());
		practiceOrderSettlementService.cancelCurrentRunPendingLimitOrders(
			userId, attempt.getId(), attempt.getRunNumber());

		LocalDateTime now = LocalDateTime.now(clock);
		attempt.advanceScenarioScript(TutorialScenarioScriptId.CRYPTO_STORY_V1, now);

		return toResponse(attempt, tutorialAccountFor(userId, market));
	}

	// 이 서비스는 튜토리얼 계좌를 바꾸지 않으므로(ORDERBASICS-019) 잠그지 않고 읽는다. 계좌가 없는 경로는
	// 진입이 이미 계좌를 만들어 두므로 사실상 도달하지 않는다 — PracticeAttemptService.tutorialAccountFor와
	// 같은 폴백이다.
	private TutorialAccount tutorialAccountFor(Long userId, Market market) {
		return tutorialAccountService.find(userId, market)
			.orElseGet(() -> tutorialAccountService.getOrCreateForUpdate(
				userId, market, LocalDateTime.now(clock)));
	}

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt, TutorialAccount tutorialAccount) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(
			attempt,
			snapshot,
			// 방금 순보유수량 0을 확인했으므로 잠겨 있지 않다 — 다시 조회하지 않는다(attempt를 잠근
			// 트랜잭션 안이라 그 사이 매수 체결이 끼어들 수 없다).
			false,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}
}
