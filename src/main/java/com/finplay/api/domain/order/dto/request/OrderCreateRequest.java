package com.finplay.api.domain.order.dto.request;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.OrderSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record OrderCreateRequest(
	@NotNull(message = "시장은 필수입니다.")
	Market market,
	@NotNull(message = "종목 ID는 필수입니다.")
	Long instrumentId,
	@NotNull(message = "매수/매도 구분은 필수입니다.")
	OrderSide side,
	@NotBlank(message = "주문 유형은 필수입니다.") @Size(max = 20, message = "주문 유형은 최대 20자까지 입력할 수 있습니다.")
	String orderType,
	@NotNull(message = "수량은 필수입니다.")
	BigDecimal quantity) {
}
