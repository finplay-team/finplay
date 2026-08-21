// OCO 손절·익절 실행 가격선을 019 공식대로 확정하고 범위·정밀도를 검증하는 순수 계산기
package com.finplay.api.domain.order.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.order.entity.ExitPriceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * {@code ai/specs/019-exit-price-policy}의 "계산과 snapshot 규칙"을 그대로 구현한다. 일반 경로(021)와 교육
 * 경로(016)가 같은 인스턴스를 공유하며, 저장소·엔티티·요청 DTO에 의존하지 않아 단위 테스트만으로 전 조합을
 * 검증할 수 있다(019 plan.md "계산 정책").
 *
 * <p><b>{@code education.marketpractice.ReferencePriceCalculator}와의 관계:</b> 그 서비스는 026의 3단계 화면용
 * "참조 가격선"을 매 요청 재계산하는 읽기 전용 계산기로, 같은 019 공식을 쓰지만 범위·정밀도 위반을 예외로
 * 만들지 않고 {@code Optional.empty()}로 표현한다. 이 클래스는 예약을 실제로 만드는 경로라서 019가 요구한 409
 * 거부까지 책임진다. 공식을 고칠 때는 두 곳을 함께 확인한다(019 spec.md 5행).
 */
@Service
public class ExitPricePolicy {

	private static final int PRICE_SCALE = 8;
	private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
	// DECIMAL(18,8) — scale 8을 뺀 정수부 자리수 상한
	private static final int MAX_INTEGER_DIGITS = 10;
	private static final int PERCENT_POINTS = 2;

	/**
	 * 입력 방식에 따라 가격선을 확정한다. PRICE는 원본 가격을 scale 8로 정규화해 복사하고, PERCENT는 019 공식으로
	 * 계산한다. 두 방식 모두 확정 후 {@code 0 < stopLossPrice < entryPrice < takeProfitPrice}와
	 * {@code DECIMAL(18,8)} 상한을 검증하며, 위반하면 409 {@link ErrorCode#EXIT_PLAN_INVALID_PRICE_RANGE}로
	 * plan·condition·예약을 남기지 않고 거부한다.
	 */
	public ExitPriceLinesDto resolve(ExitPriceInputDto input) {
		ExitPriceLinesDto lines = input.exitPriceType() == ExitPriceType.PRICE ? copyFromPrice(input)
			: calculateFromPercent(input);
		validateRange(input.entryPrice(), lines);
		validatePrecision(lines);
		return lines;
	}

	// PRICE: 원본 절대 가격을 그대로 실행 가격선 snapshot으로 복사한다(019 spec.md "계산과 snapshot 규칙").
	private ExitPriceLinesDto copyFromPrice(ExitPriceInputDto input) {
		return new ExitPriceLinesDto(
			input.stopLoss().setScale(PRICE_SCALE, ROUNDING_MODE),
			input.takeProfit().setScale(PRICE_SCALE, ROUNDING_MODE));
	}

	/**
	 * PERCENT: {@code entryPrice × (1 ∓ rate/100)}. rate/100은 {@code movePointLeft(2)}로 오차 없이 계산하고
	 * (019 plan.md 의사코드) 최종 가격만 scale 8 HALF_UP으로 한 번 반올림한다 — 중간 단계 반올림과
	 * {@code double}·{@code float}는 사용하지 않는다.
	 */
	private ExitPriceLinesDto calculateFromPercent(ExitPriceInputDto input) {
		BigDecimal entryPrice = input.entryPrice();
		BigDecimal normalizedStopRate = input.stopLossRate().movePointLeft(PERCENT_POINTS);
		BigDecimal normalizedTakeRate = input.takeProfitRate().movePointLeft(PERCENT_POINTS);
		return new ExitPriceLinesDto(
			entryPrice.multiply(BigDecimal.ONE.subtract(normalizedStopRate)).setScale(PRICE_SCALE, ROUNDING_MODE),
			entryPrice.multiply(BigDecimal.ONE.add(normalizedTakeRate)).setScale(PRICE_SCALE, ROUNDING_MODE));
	}

	// 아주 작은 비율이 반올림되어 entryPrice와 같아지는 경우까지 여기서 걸러낸다(019 spec.md).
	private void validateRange(BigDecimal entryPrice, ExitPriceLinesDto lines) {
		if (lines.stopLossPrice().signum() <= 0
			|| lines.stopLossPrice().compareTo(entryPrice) >= 0
			|| lines.takeProfitPrice().compareTo(entryPrice) <= 0) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
		}
	}

	// DB DataIntegrityViolation을 정상 검증 경로로 쓰지 않기 위해 저장 전에 정수부 자리수를 직접 확인한다(019 plan.md).
	private void validatePrecision(ExitPriceLinesDto lines) {
		if (exceedsColumnPrecision(lines.stopLossPrice()) || exceedsColumnPrecision(lines.takeProfitPrice())) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
		}
	}

	private boolean exceedsColumnPrecision(BigDecimal price) {
		return price.precision() - price.scale() > MAX_INTEGER_DIGITS;
	}
}
