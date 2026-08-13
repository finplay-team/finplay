// OCO 손절·익절 예약 생성 요청 DTO — intentionId 유무로 일반·교육 두 경로를 통합한다(021 plan.md "API 설계")
package com.finplay.api.order.dto.request;

import com.finplay.api.order.domain.ExitPriceType;
import jakarta.validation.constraints.NotNull;
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
	@NotNull(message = "수량은 필수입니다.")
	BigDecimal quantity,
	ExitPriceType exitPriceType,
	BigDecimal stopLoss,
	BigDecimal takeProfit,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate) {
}
