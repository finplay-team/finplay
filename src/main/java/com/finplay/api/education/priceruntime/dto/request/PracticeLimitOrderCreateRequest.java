// 코인 가상 가격 세션에 귀속된 교육 전용 지정가 BUY 주문 생성 요청 — side는 입력받지 않고 서버가 BUY로 고정한다
package com.finplay.api.education.priceruntime.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record PracticeLimitOrderCreateRequest(
	@NotNull(message = "가격 세션 ID는 필수입니다.") @Positive(message = "가격 세션 ID는 양수여야 합니다.")
	Long practicePriceSessionId,
	@NotNull(message = "종목 ID는 필수입니다.") @Positive(message = "종목 ID는 양수여야 합니다.")
	Long instrumentId,
	@NotNull(message = "수량은 필수입니다.")
	BigDecimal quantity,
	@NotNull(message = "지정가는 필수입니다.")
	BigDecimal limitPrice) {
}
