// 샘플 주문을 현재 attempt 실행 세대에 귀속하고 최초 BUY 위험 스냅샷을 원자 생성하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.ExitPreset;
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
import com.finplay.api.order.service.PracticeOrderFillContextDto;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptOrderAttributionService implements PracticeOrderAttributionPort {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final ReferencePriceCalculator referencePriceCalculator;
	private final HoldingService holdingService;
	private final Clock clock;

	@Transactional
	@Override
	public Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument) {
		if (!instrument.isTutorialSample()) {
			return Optional.empty();
		}
		Optional<PracticeAttempt> foundAttempt = practiceAttemptRepository
			.findByUserIdAndMarketForUpdate(userId, instrument.getMarket());
		if (foundAttempt.isEmpty()) {
			return Optional.empty();
		}
		PracticeAttempt attempt = foundAttempt.get();
		validateCurrentRun(attempt, instrument, attempt.getRunNumber());
		return Optional.of(new PracticeOrderAttributionDto(
			attempt.getId(), attempt.getRunNumber(),
			canonicalPriceService.canonicalPrice(attempt, LocalDateTime.now(clock))));
	}

	@Transactional
	@Override
	public PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt) {
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(attribution.attemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (!attempt.getUserId().equals(attribution.userId())
			|| attempt.getInstrument() == null
			|| !attempt.getInstrument().getId().equals(attribution.instrumentId())) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		boolean currentRun = attempt.getStatus() == PracticeAttemptStatus.IN_PROGRESS
			&& attempt.getRunNumber() == attribution.runNumber();
		return new PracticeOrderFillContextDto(
			currentRun, currentRun ? canonicalPriceService.canonicalPrice(attempt, pricedAt) : null);
	}

	/**
	 * BUY 체결마다 불리며 <b>진입당 1회만</b> snapshot을 만든다(042 EXITPRESET-020).
	 *
	 * <p>가드가 이 메서드에서 가장 중요한 한 줄이다. 없으면 보유 중 추가 매수가 (1) 5번이 붙일 예약의
	 * {@code validateNoPendingPlan} 409로 매수를 통째로 실패시키고, (2) 새 snapshot으로 기준선을 갱신해
	 * 039의 "체결가 기준 고정" 규칙을 깨고, (3) 평단 이동으로 041 SCENARIO-006a의 루머 분기를 무너뜨린다.
	 *
	 * <p><b>"직전 순보유수량"은 역산한다.</b> {@code OrderExecutionService}·{@code LimitOrderFillService}가
	 * 모두 {@code applyBuyTrade}를 <b>먼저</b> 부른 뒤 여기로 오므로, 이 시점 holding에는 이번 체결이 이미
	 * 반영돼 있다. 따라서 직전 값은 {@code 현재 순보유수량 − 이번 체결 수량}이다.
	 */
	@Transactional
	@Override
	public void createRiskSnapshotOnBuyFill(Order order, Trade trade, LocalDateTime createdAt) {
		if (order.getPracticeAttemptId() == null || order.getSide() != OrderSide.BUY) {
			return;
		}
		PracticeAttempt attempt = practiceAttemptRepository.findByIdForUpdate(order.getPracticeAttemptId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		validateCurrentRun(attempt, order.getInstrument(), order.getPracticeAttemptRunNumber());
		if (heldBeforeThisFill(attempt, trade).signum() > 0) {
			return;
		}

		// 미선택이면 기본 프리셋으로 확정한다 — 이 시점에 값이 정해져야 그 뒤 프리셋을 바꿔도 이미 만들어진
		// 진입의 기준선이 흔들리지 않는다(042 EXITPRESET-002·003).
		ExitPreset preset = attempt.getExitPreset() == null ? ExitPreset.DEFAULT : attempt.getExitPreset();
		ReferencePriceLines lines = referencePriceCalculator.calculateFromPreset(trade.getPrice(), preset);
		int entrySequence = Math.toIntExact(practiceRiskSnapshotRepository
			.countByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())) + 1;
		practiceRiskSnapshotRepository.save(PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			entrySequence,
			preset,
			trade,
			// calculateFromPreset이 체결가를 scale 8로 먼저 정규화하고 그 값으로 두 선을 만든다. 저장되는
			// entry_price도 같은 값이어야 화면의 세 숫자가 같은 기준 위에 선다.
			referencePriceCalculator.normalizeEntryPrice(trade.getPrice()),
			lines.referenceStopLossPrice(),
			lines.referenceTakeProfitPrice(),
			createdAt));
	}

	// 042 EXITPRESET-003의 프리셋 잠금, 041의 대기 구간 탈출 판정과 같은 산출식을 쓴다.
	private BigDecimal heldBeforeThisFill(PracticeAttempt attempt, Trade trade) {
		BigDecimal heldNow = holdingService.findNetQuantity(
			attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId());
		return heldNow.subtract(trade.getQuantity());
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
