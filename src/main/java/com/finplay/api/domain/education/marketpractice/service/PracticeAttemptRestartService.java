package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.PracticeRunRestartCommand;
import com.finplay.api.domain.order.service.PracticeRunRestartOrderService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptRestartService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeRunRestartOrderService practiceRunRestartOrderService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final TutorialAccountService tutorialAccountService;
	private final Clock clock;

	@Transactional
	public PracticeAttemptResponse restart(Long userId, Market market) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		LocalDateTime restartedAt = LocalDateTime.now(clock);
		Instrument cleanupTarget = resolveCleanupInstrument(attempt);
		practiceRunRestartOrderService.cleanupCurrentRun(new PracticeRunRestartCommand(
			attempt.getId(), attempt.getRunNumber(), userId, market,
			cleanupTarget == null ? null : cleanupTarget.getId(),
			cleanupTarget == null ? null : canonicalPriceService.canonicalPrice(attempt, restartedAt),
			restartedAt));
		attempt.restart(restartedAt);
		TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
			userId, market, restartedAt);
		return toResponse(attempt, tutorialAccount);
	}

	private Instrument resolveCleanupInstrument(PracticeAttempt attempt) {
		Instrument instrument = attempt.getInstrument();
		if (instrument == null) {
			return null;
		}
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED && !instrument.isTutorialSample()) {
			return null;
		}
		return instrument;
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
