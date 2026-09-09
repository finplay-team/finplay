package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
class TradeFeedbackWriter {

	private final TradeService tradeService;

	private final TradeFeedbackRepository tradeFeedbackRepository;

	@Transactional
	TradeFeedback create(
		Long userId,
		Long tradeId,
		NarrativeResultDto narrative,
		String journalFingerprint,
		LocalDateTime generatedAt) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		return tradeFeedbackRepository.save(
			TradeFeedback.create(trade, narrative.narrative(), narrative.source(), journalFingerprint, generatedAt));
	}

	@Transactional
	void applyRegenerated(
		Long tradeId,
		NarrativeResultDto narrative,
		String journalFingerprint,
		RegenerationReasons reasons,
		LocalDateTime generatedAt) {
		tradeFeedbackRepository.findByTradeId(tradeId).ifPresentOrElse(
			feedback -> {
				if (reasons.gate()) {
					feedback.applyRegeneratedNarrative(
						narrative.narrative(), narrative.source(), journalFingerprint, generatedAt);
					if (reasons.journal()) {
						feedback.countJournalRegeneration();
					}
					return;
				}
				feedback.applyJournalRegeneratedNarrative(
					narrative.narrative(), narrative.source(), journalFingerprint, generatedAt);
			},
			() -> log.debug("재생성 대상 회고 행이 사라져 저장을 건너뛴다. tradeId={}", tradeId));
	}

	@Transactional
	void recordFailedRegeneration(Long tradeId, RegenerationReasons reasons) {
		tradeFeedbackRepository.findByTradeId(tradeId).ifPresentOrElse(
			feedback -> {
				if (reasons.gate()) {
					feedback.recordFailedRegeneration();
				}
				if (reasons.journal()) {
					feedback.countJournalRegeneration();
				}
			},
			() -> log.debug("재생성 대상 회고 행이 사라져 재시도 횟수 누적을 건너뛴다. tradeId={}", tradeId));
	}
}
