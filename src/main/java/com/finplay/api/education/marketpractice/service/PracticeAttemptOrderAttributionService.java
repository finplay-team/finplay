// 샘플 주문을 현재 attempt 실행 세대에 귀속하고 최초 BUY 위험 스냅샷을 원자 생성하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeOrderAttributionDto;
import com.finplay.api.order.service.PracticeOrderAttributionPort;
import com.finplay.api.order.service.PracticeOrderFillAttributionDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptOrderAttributionService implements PracticeOrderAttributionPort {

	private static final int PRICE_SCALE = 8;
	private static final BigDecimal STOP_LOSS_MULTIPLIER = new BigDecimal("0.97");
	private static final BigDecimal TAKE_PROFIT_MULTIPLIER = new BigDecimal("1.05");

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;

	@Transactional
	@Override
	public Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument) {
		if (!instrument.isTutorialSample()) {
			return Optional.empty();
		}
		PracticeAttempt attempt = practiceAttemptRepository
			.findByUserIdAndMarketForUpdate(userId, instrument.getMarket())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		validateCurrentRun(attempt, instrument, attempt.getRunNumber());
		return Optional.of(new PracticeOrderAttributionDto(attempt.getId(), attempt.getRunNumber()));
	}

	@Transactional
	@Override
	public boolean lockForFill(PracticeOrderFillAttributionDto attribution) {
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(attribution.attemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (!attempt.getUserId().equals(attribution.userId())
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(attribution.instrumentId())) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		return attempt.getStatus() == PracticeAttemptStatus.IN_PROGRESS
			&& attempt.getRunNumber() == attribution.runNumber();
	}

	@Transactional
	@Override
	public void createFirstBuyRiskSnapshot(Order order, Trade trade, LocalDateTime createdAt) {
		if (order.getPracticeAttemptId() == null || order.getSide() != OrderSide.BUY) {
			return;
		}
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(order.getPracticeAttemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		validateCurrentRun(attempt, order.getInstrument(), order.getPracticeAttemptRunNumber());
		if (practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())
			.isPresent()) {
			return;
		}

		BigDecimal entryPrice = trade.getPrice().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		BigDecimal stopLossPrice = entryPrice.multiply(STOP_LOSS_MULTIPLIER)
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		BigDecimal takeProfitPrice = entryPrice.multiply(TAKE_PROFIT_MULTIPLIER)
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		practiceRiskSnapshotRepository.save(PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			trade,
			entryPrice,
			stopLossPrice,
			takeProfitPrice,
			createdAt));
	}

	private void validateCurrentRun(PracticeAttempt attempt, Instrument instrument, long runNumber) {
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.IN_PROGRESS
			|| attempt.getRunNumber() != runNumber
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(instrument.getId())) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
	}
}
