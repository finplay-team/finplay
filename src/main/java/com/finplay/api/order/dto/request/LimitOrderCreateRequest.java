// 코인 지정가 매수·매도 주문 생성 요청 DTO
package com.finplay.api.order.dto.request;

import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.OrderSide;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LimitOrderCreateRequest(
	@NotNull(message = "시장은 필수입니다.")
	Market market,
	@NotNull(message = "종목 ID는 필수입니다.")
	Long instrumentId,
	@NotNull(message = "매수/매도 구분은 필수입니다.")
	OrderSide side,
	@NotNull(message = "수량은 필수입니다.")
	BigDecimal quantity,
	@NotNull(message = "지정가는 필수입니다.")
	BigDecimal limitPrice) {
}
