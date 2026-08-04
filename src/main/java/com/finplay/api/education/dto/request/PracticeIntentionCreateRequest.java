// 투자 실습 사전 의도 생성 입력과 수량·가격 정밀도 검증을 정의하는 요청 DTO
package com.finplay.api.education.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record PracticeIntentionCreateRequest(
	@NotNull(message = "종목 ID는 필수입니다.") @Positive(message = "종목 ID는 양수여야 합니다.")
	Long instrumentId,
	@NotNull(message = "수량은 필수입니다.") @Positive(message = "수량은 양수여야 합니다.") @Digits(integer = 22, fraction = 8, message = "수량 형식이 올바르지 않습니다.")
	BigDecimal quantity,
	@NotNull(message = "손절가는 필수입니다.") @Positive(message = "손절가는 양수여야 합니다.") @Digits(integer = 10, fraction = 8, message = "손절가 형식이 올바르지 않습니다.")
	BigDecimal stopLoss,
	@NotNull(message = "익절가는 필수입니다.") @Positive(message = "익절가는 양수여야 합니다.") @Digits(integer = 10, fraction = 8, message = "익절가 형식이 올바르지 않습니다.")
	BigDecimal takeProfit) {
}
