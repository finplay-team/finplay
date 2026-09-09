package com.finplay.api.domain.education.marketpractice.dto.request;

import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PracticeAttemptExitRatesUpdateRequest(
	@NotNull(message = "손절 비율은 필수입니다.") @DecimalMin(value = "2", message = "손절 비율은 2% 이상이어야 합니다.") @DecimalMax(value = "5", message = "손절 비율은 5% 이하여야 합니다.") @Digits(integer = 1, fraction = 1, message = "손절 비율은 소수 첫째 자리까지 입력할 수 있습니다.")
	BigDecimal stopLossRate,

	@NotNull(message = "익절 비율은 필수입니다.") @DecimalMin(value = "3", message = "익절 비율은 3% 이상이어야 합니다.") @DecimalMax(value = "8", message = "익절 비율은 8% 이하여야 합니다.") @Digits(integer = 1, fraction = 1, message = "익절 비율은 소수 첫째 자리까지 입력할 수 있습니다.")
	BigDecimal takeProfitRate) {

	public ExitRates toExitRates() {
		return ExitRates.of(stopLossRate, takeProfitRate);
	}
}
