// 일반·교육 두 경로가 공유하는 OCO 예약 생성 엔진 — holding 잠금·수량 예약·plan·condition 저장을 한 트랜잭션으로 처리한다
package com.finplay.api.order.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanCondition;
import com.finplay.api.order.domain.ExitPlanConditionType;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code docs/specs/021-general-risk-management-oco} plan.md "일반 경로 검증 순서" 3~9단계 중 <b>경로와 무관한
 * 부분</b>만 담당한다. 잠금 순서는 두 경로·트리거·취소가 모두 공유하는 {@code holding → plan}이다.
 *
 * <p>요청 파싱, {@code intentionId} 유무 분기, 경로별 필드 조합 검증, 시장 범위(코인 전용) 검증,
 * {@code Idempotency-Key} 처리는 호출부가 수행하고 결과를 {@link ExitPlanCreateCommandDto}로 넘긴다.
 *
 * <p>예약 원장은 {@code docs/specs/015-limit-order}가 이미 구현한 {@code Holding.reservedQuantity} 원장을 그대로
 * 재사용한다 — 새 원장을 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ExitPlanCreationService {

	private final PortfolioSellService portfolioSellService;
	private final PriceQueryService priceQueryService;
	private final ExitPricePolicy exitPricePolicy;
	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final Clock clock;

	@Transactional
	public ExitPlan create(ExitPlanCreateCommandDto command) {
		// 3단계 — holding 잠금. 이후 모든 검증이 이 락 아래에서 이뤄져 TOCTOU가 없다.
		Holding holding = lockHolding(command.holding());

		// 4단계 — holding당 PENDING 1건 불변식(경로 무관 엔진 규칙). 5단계보다 먼저 판정한다:
		// 기존 PENDING plan이 수량을 예약하고 있으면 availableQuantity도 함께 부족해지므로, 순서를 바꾸면
		// "이미 예약이 있다"는 원인이 INSUFFICIENT_QTY로 가려진다(plan.md 4·5단계 순서).
		validateNoPendingPlan(holding);

		// 5단계 — 015 예약 원장의 availableQuantity(=quantity-reservedQuantity)만 검증한다(부분 수량 허용).
		validateAvailableQuantity(holding, command);

		// 6·7단계 — 확정 가격선 계산과 범위·정밀도 검증(019 정책 재사용).
		ExitPriceLinesDto lines = exitPricePolicy.resolve(command.priceInput());

		// 8단계 — baseline 확정. 일반·교육 경로는 서버 유효 현재가를 읽고, 시세가 없으면 여기서 409가 나
		// 아래 예약·저장에 도달하지 않으므로 plan·condition·예약 흔적이 남지 않는다.
		ExitPlanBaselineDto baseline = resolveBaseline(command, holding);

		// 9단계 — 예약과 저장을 같은 트랜잭션에서 커밋한다.
		return reserveAndSave(command, holding, lines, baseline);
	}

	// 호출부가 이미 존재·소유권을 확인한 holding을 다시 잠근다 — 다른 도메인 repository를 직접 주입하지 않기
	// 위해 015가 만든 portfolio service의 잠금 접근자를 재사용한다(ADR-0002).
	private Holding lockHolding(Holding holding) {
		return portfolioSellService.getHoldingForUpdateForExitPlanCreation(holding.getAccount(),
			holding.getInstrument());
	}

	// 튜토리얼 자동 예약은 호출부가 041 대본의 canonical price를 주입한다 — 이 자리의 기본 경로는 샘플
	// 종목이면 사인파 항시 시세로 분기해 대본과 무관한 값을 baseline_price에 영속한다(042 plan §자동 예약 생성).
	private ExitPlanBaselineDto resolveBaseline(ExitPlanCreateCommandDto command, Holding holding) {
		if (command.isPracticePath()) {
			return new ExitPlanBaselineDto(command.practiceOrigin().baselinePrice(), LocalDateTime.now(clock));
		}
		PriceQuoteDto quote = priceQueryService.getPrice(holding.getInstrument().getId());
		return new ExitPlanBaselineDto(quote.price(), quote.sourceTime());
	}

	private void validateNoPendingPlan(Holding holding) {
		if (exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_ALREADY_EXISTS);
		}
	}

	private void validateAvailableQuantity(Holding holding, ExitPlanCreateCommandDto command) {
		if (holding.getAvailableQuantity().compareTo(command.quantity()) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
		}
	}

	private ExitPlan reserveAndSave(
		ExitPlanCreateCommandDto command, Holding holding, ExitPriceLinesDto lines, ExitPlanBaselineDto baseline) {
		LocalDateTime now = LocalDateTime.now(clock);
		holding.reserveQuantity(command.quantity());

		ExitPlan plan = exitPlanRepository.save(newExitPlan(command, holding, lines, baseline, now));
		exitPlanConditionRepository.save(
			ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, lines.stopLossPrice(), now));
		exitPlanConditionRepository.save(
			ExitPlanCondition.create(plan, ExitPlanConditionType.TAKE_PROFIT, lines.takeProfitPrice(), now));
		return plan;
	}

	private ExitPlan newExitPlan(
		ExitPlanCreateCommandDto command, Holding holding, ExitPriceLinesDto lines, ExitPlanBaselineDto baseline,
		LocalDateTime now) {
		ExitPriceInputDto priceInput = command.priceInput();
		// AVAILABLE 판정을 받은 quote는 두 공급자 모두 관측 시각을 함께 주지만, baseline_observed_at이 NOT NULL이라
		// 방어적으로 현재 시각으로 대체한다.
		LocalDateTime baselineObservedAt = baseline.observedAt() != null ? baseline.observedAt() : now;
		if (command.isPracticePath()) {
			ExitPlanPracticeOriginDto practiceOrigin = command.practiceOrigin();
			return ExitPlan.createPractice(
				command.user(),
				holding,
				holding.getInstrument(),
				command.quantity(),
				priceInput.entryPrice(),
				priceInput.exitPriceType(),
				priceInput.stopLossRate(),
				priceInput.takeProfitRate(),
				lines.stopLossPrice(),
				lines.takeProfitPrice(),
				baseline.price(),
				baselineObservedAt,
				command.requestHash(),
				practiceOrigin.attemptId(),
				practiceOrigin.runNumber(),
				now);
		}
		if (!command.isEducationalPath()) {
			return ExitPlan.createGeneral(
				command.user(),
				holding,
				holding.getInstrument(),
				command.quantity(),
				priceInput.entryPrice(),
				priceInput.exitPriceType(),
				priceInput.stopLossRate(),
				priceInput.takeProfitRate(),
				lines.stopLossPrice(),
				lines.takeProfitPrice(),
				baseline.price(),
				baselineObservedAt,
				command.requestHash(),
				now);
		}
		ExitPlanEducationalOriginDto origin = command.educationalOrigin();
		return ExitPlan.createEducational(
			command.user(),
			holding,
			origin.intentionId(),
			origin.intentionInstanceKey(),
			origin.buyTrade(),
			holding.getInstrument(),
			command.quantity(),
			priceInput.entryPrice(),
			priceInput.exitPriceType(),
			priceInput.stopLossRate(),
			priceInput.takeProfitRate(),
			lines.stopLossPrice(),
			lines.takeProfitPrice(),
			baseline.price(),
			baselineObservedAt,
			command.requestHash(),
			now);
	}
}
