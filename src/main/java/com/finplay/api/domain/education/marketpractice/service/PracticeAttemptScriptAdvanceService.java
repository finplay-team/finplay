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
		if (!attempt.usesScenarioScript() || attempt.scenarioScriptId() == TutorialScenarioScriptId.CRYPTO_STORY_V1) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
		PracticeStageProgressResponse progress = practiceStageProgressCalculationService.calculate(attempt);
		if (!progress.marketBuySellCompleted() || !progress.limitBuySellCompleted()) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
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
			false,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}
}
