// 완료 attempt replay와 미완료 attempt의 원자적 실행 세대 재시작을 조정하는 교육 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.PracticeRunRestartCommand;
import com.finplay.api.domain.order.service.PracticeRunRestartOrderService;
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
		// cleanupCurrentRun이 같은 트랜잭션 안에서 튜토리얼 계좌를 이미 리셋했으므로(TUTORIAL-CASH-ISOL-006),
		// 여기서는 그 결과를 다시 조회해 응답에 실어 보낸다(TUTORIAL-CASH-ISOL-011).
		TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
			userId, market, restartedAt);
		return toResponse(attempt, tutorialAccount);
	}

	// 샌드박스 종목 도입(V32) 이전에 실제 종목으로 완료한 legacy 완료자는 진입 시 실제 종목을 심은 replay
	// attempt를 받는다. 이 attempt에는 귀속된 주문 원장이 없고 실제 포트폴리오는 정리 대상이 아니므로
	// 종목 미선택 재시작으로 넘긴다. 귀속 주문이 남아 있으면 정리 경로가 그대로 409로 막는다 (이슈 #433).
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
			// 재시작은 순체결수량을 보상 매도로 청산한 뒤에만 이 응답에 도달하므로 보유가 0이다
			// — 조회하지 않고 false로 둔다(042 EXITPRESET-009: 프리셋도 함께 기본값으로 돌아간다).
			false,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}
}
