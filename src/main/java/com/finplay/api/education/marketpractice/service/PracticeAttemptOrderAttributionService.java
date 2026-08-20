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
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.TutorialScenarioScriptId;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.order.service.ExitPlanCreationService;
import com.finplay.api.order.service.ExitPlanPracticeOriginDto;
import com.finplay.api.order.service.ExitPriceInputDto;
import com.finplay.api.order.service.PracticeOrderAttributionDto;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.order.service.PracticeOrderAttributionPort;
import com.finplay.api.order.service.PracticeOrderFillAttributionDto;
import com.finplay.api.order.service.PracticeOrderFillContextDto;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
	private final TradeService tradeService;
	// 예약 생성에 넘길 holding 엔티티를 얻는 용도다 — 순보유수량 판정에는 쓰지 않는다(실행 세대 범위가 아니다).
	private final HoldingService holdingService;
	private final ExitPlanCreationService exitPlanCreationService;
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
	 * <p><b>"직전 순보유수량"은 역산한다.</b> 판정은 holding 행이 아니라 <b>현재 실행 세대의 체결 원장</b>을
	 * 읽으므로(실행 세대를 넘어 누적되는 값을 쓰면 이전 실행의 잔여 보유가 새 진입을 추가 매수로 오판한다),
	 * 성립해야 하는 전제는 "두 체결 경로가 모두 {@code tradeRepository.save(trade)}를 이 호출보다 <b>먼저</b>
	 * 한다"이다 — {@code OrderExecutionService}·{@code LimitOrderFillService} 둘 다 만족한다. 따라서 직전
	 * 값은 {@code 현재 실행 세대 순량 − 이번 체결 수량}이다.
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

		// STOCK은 snapshot(참조선)까지만이다(EXITPRESET-018) — 실제 거래 화면에서도 OCO는 코인 전용이고
		// 주식은 OCO 경로 자체가 없다.
		if (attempt.getMarket() == Market.CRYPTO && automaticExitPlanAllowed(attempt)) {
			createAutomaticExitPlan(attempt, trade, preset, entrySequence, createdAt);
		}
	}

	/**
	 * 2단계 대본(주문 방법 학습) 실행에서만 자동 손절·익절 예약을 건너뛴다(049 ORDERBASICS-022).
	 *
	 * <p><b>위험 기준선(snapshot)은 위에서 그대로 만든다.</b> 건너뛰는 것은 예약뿐이다 — 기준선까지
	 * 빼면 {@code PracticeAttemptEvidenceService.requireCurrentRun}이 {@code PRACTICE_EVIDENCE_MISSING}으로
	 * 던져 관찰·복기가 통째로 깨지고 진입별 대조 배열도 빈다.
	 *
	 * <p><b>왜 예약을 만들지 않는가.</b> 손절·익절은 3단계에서 배우는 것이라 아직 배우지 않은 기능이
	 * 2단계에서 몰래 작동하는 것이 이상했고, ±12%를 가상 20분에 도는 이 대본에서 기본 프리셋은
	 * <b>매수 6~9초 만에 발동한다.</b> 그러면 "매수·매도를 직접 눌러 본다"는 학습 목표가 사라지고,
	 * 자동 청산된 매도는 왕복으로 세지 않으므로(#503) 다음 단계가 영영 열리지 않는다.
	 *
	 * <p><b>판정은 이 run의 대본 하나로만 한다.</b> 단계 진행 상태로 가르면 판정이 순환하고 같은 run 안에서
	 * 예약이 생겼다 안 생겼다 한다. 대본을 쓰지 않는 실행(생성기 버전 1)은 {@code null}이라 예전대로
	 * 예약이 생긴다. <b>2단계 대본이 늘어나면 이 판정도 함께 늘려야 한다</b> — 빠뜨리면 그 대본에서
	 * 예약이 조용히 되살아난다.
	 */
	private boolean automaticExitPlanAllowed(PracticeAttempt attempt) {
		return attempt.scenarioScriptId() != TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1;
	}

	/**
	 * 매수 체결과 <b>같은 트랜잭션</b>에서 OCO 예약을 만든다(042 EXITPRESET-012) — 예약 생성이 실패하면
	 * 매수도 함께 롤백돼 "기준선은 있는데 예약이 없는" 상태가 남지 않는다.
	 *
	 * <p><b>공용 엔진을 직접 부른다.</b> 호출부인 {@code ExitPlanService.create}에는 047(이슈 #461)이 넣은
	 * 샌드박스 종목 차단이 있고, 그 차단은 엔진에 두지 않기로 021 RISK-OCO-014가 정했다 — 교육 경로가
	 * 재접합될 때 자기 차단에 막히지 않게 하려고 의도적으로 비워 둔 자리다. 042의 예약은 attempt·실행
	 * 세대로 귀속되고 체결도 tick 정산 경로를 타므로, 그 차단이 막으려던 파손(귀속 없는 청산)을 애초에
	 * 만들지 않는다. <b>나중에 누군가 이 차단을 엔진으로 옮기면 042가 통째로 깨진다.</b>
	 */
	private void createAutomaticExitPlan(
		PracticeAttempt attempt, Trade trade, ExitPreset preset, int entrySequence, LocalDateTime createdAt) {
		Long holdingId = holdingService
			.findHoldingId(attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		Holding holding = holdingService.findHoldingForOwner(attempt.getUserId(), holdingId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		// ExitPricePolicy의 PERCENT 경로는 체결가를 정규화하지 않고 그대로 곱한다. snapshot과 같은 scale 8
		// 값을 넘겨야 화면 기준선과 실제 체결선이 scale 9 이하 자리에서 갈리지 않는다(042 tasks 5번).
		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPercent(
			referencePriceCalculator.normalizeEntryPrice(trade.getPrice()),
			preset.stopLossRate(),
			preset.takeProfitRate());
		exitPlanCreationService.create(ExitPlanCreateCommandDto.practice(
			trade.getAccount().getUser(),
			holding,
			trade.getQuantity(),
			priceInput,
			requestHash(attempt, entrySequence),
			new ExitPlanPracticeOriginDto(
				attempt.getId(),
				attempt.getRunNumber(),
				canonicalPriceService.canonicalPrice(attempt, createdAt))));
	}

	// exit_plans.request_hash가 CHAR(64)라 SHA-256 hex를 넣는다. **멱등키가 아니다** — 그 컬럼에 UNIQUE가
	// 없고 엔진도 읽지 않는다. 실제 중복 방어는 엔진 4단계의 validateNoPendingPlan과, attempt를 잠근
	// 트랜잭션 안에서 entry_sequence를 산출하는 직렬화다. 이 값은 감사용 snapshot이다.
	private String requestHash(PracticeAttempt attempt, int entrySequence) {
		String source = attempt.getId() + ":" + attempt.getRunNumber() + ":" + entrySequence;
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
		}
	}

	// 042 EXITPRESET-003의 프리셋 잠금, 041의 대기 구간 탈출 판정과 같은 산출식을 쓴다 — 현재 실행 세대의
	// 순량이라 이전 실행에서 넘어온 보유가 새 진입을 "추가 매수"로 오판하지 않는다.
	private BigDecimal heldBeforeThisFill(PracticeAttempt attempt, Trade trade) {
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber())
			.subtract(trade.getQuantity());
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
