package com.finplay.api.domain.order.dto.request;

import com.finplay.api.domain.order.entity.ExitPriceType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ExitPlanCreateRequest(
	Long intentionId,
	Long buyTradeId,
	Long instrumentId,
	Long holdingId,
	@NotNull(message = "수량은 필수입니다.") @Positive(message = "수량은 0보다 커야 합니다.")
	BigDecimal quantity,
	ExitPriceType exitPriceType,
	@Digits(integer = 10, fraction = 8, message = "손절가는 정수부 10자리·소수부 8자리 이하여야 합니다.") @Positive(message = "손절가는 0보다 커야 합니다.")
	BigDecimal stopLoss,
	@Digits(integer = 10, fraction = 8, message = "익절가는 정수부 10자리·소수부 8자리 이하여야 합니다.") @Positive(message = "익절가는 0보다 커야 합니다.")
	BigDecimal takeProfit,
	@Digits(integer = 3, fraction = 4, message = "손절률은 정수부 3자리·소수부 4자리 이하여야 합니다.") @Positive(message = "손절률은 0보다 커야 합니다.")
	BigDecimal stopLossRate,
	@Digits(integer = 4, fraction = 4, message = "익절률은 정수부 4자리·소수부 4자리 이하여야 합니다.") @Positive(message = "익절률은 0보다 커야 합니다.")
	BigDecimal takeProfitRate) {
}
