// 시장가 매수 주문 생성 요청 DTO
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
	// enum이 아닌 String으로 받는다 — OrderType은 MARKET만 존재해 "LIMIT" 같은 유효 리터럴이
	// JSON 파싱 단계에서 400으로 죽어버리면 서비스가 422 UNSUPPORTED_ORDER_TYPE을 낼 수 없다.
	@NotBlank(message = "주문 유형은 필수입니다.") @Size(max = 20, message = "주문 유형은 최대 20자까지 입력할 수 있습니다.")
	String orderType,
	@NotNull(message = "수량은 필수입니다.")
	BigDecimal quantity) {
}
