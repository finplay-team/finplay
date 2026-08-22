// OCO 손절·익절 예약 생성 요청 DTO — intentionId 유무로 일반·교육 두 경로를 통합한다(021 plan.md "API 설계")
package com.finplay.api.domain.order.dto.request;

import com.finplay.api.domain.order.entity.ExitPriceType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 이 이슈(#348)는 {@code intentionId}를 생략한 <b>일반 경로</b> 검증만 구현한다.
 * {@code intentionId}를 지정하는 교육 경로는 후속 이슈("교육 경로 재접합")가 채운다 — 지금은 값이 오면 호출부가
 * 400 {@code VALIDATION_ERROR}로 명확히 거부한다.
 *
 * <p>일반 경로 필수·금지 조합(021 plan.md 필드 표): {@code holdingId}·{@code quantity}·{@code exitPriceType} 필수,
 * {@code exitPriceType=PRICE}면 {@code stopLoss}·{@code takeProfit} 필수(rate 금지), {@code PERCENT}면 그 반대.
 * {@code buyTradeId}·{@code instrumentId}는 일반 경로에 존재하지 않는다(포함되면 400).
 */
public record ExitPlanCreateRequest(
	Long intentionId,
	Long buyTradeId,
	Long instrumentId,
	Long holdingId,
	@NotNull(message = "수량은 필수입니다.") @Positive(message = "수량은 0보다 커야 합니다.")
	BigDecimal quantity,
	ExitPriceType exitPriceType,
	// exit_plans.stop_loss_price/take_profit_price DECIMAL(18,8) 기준 — PRICE 모드 원본 값이 그대로 저장된다.
	// null 허용(@Digits·@Positive는 null이면 통과) — PRICE/PERCENT 필수 여부는 ExitPlanService가 검증한다.
	@Digits(integer = 10, fraction = 8, message = "손절가는 정수부 10자리·소수부 8자리 이하여야 합니다.") @Positive(message = "손절가는 0보다 커야 합니다.")
	BigDecimal stopLoss,
	@Digits(integer = 10, fraction = 8, message = "익절가는 정수부 10자리·소수부 8자리 이하여야 합니다.") @Positive(message = "익절가는 0보다 커야 합니다.")
	BigDecimal takeProfit,
	// exit_plans.stop_loss_rate DECIMAL(7,4) 기준 — 계산된 가격이 아니라 원본 rate 자체가 이 컬럼에 저장된다.
	@Digits(integer = 3, fraction = 4, message = "손절률은 정수부 3자리·소수부 4자리 이하여야 합니다.") @Positive(message = "손절률은 0보다 커야 합니다.")
	BigDecimal stopLossRate,
	// exit_plans.take_profit_rate DECIMAL(8,4) 기준.
	@Digits(integer = 4, fraction = 4, message = "익절률은 정수부 4자리·소수부 4자리 이하여야 합니다.") @Positive(message = "익절률은 0보다 커야 합니다.")
	BigDecimal takeProfitRate) {
}
