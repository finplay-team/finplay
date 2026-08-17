// 완료 attempt replay와 미완료 attempt의 원자적 실행 세대 재시작을 조정하는 교육 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.service.PracticeRunRestartCommand;
import com.finplay.api.order.service.PracticeRunRestartOrderService;
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
		return toResponse(attempt);
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

	private PracticeAttemptResponse toResponse(PracticeAttempt attempt) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(attempt, snapshot);
	}
}
