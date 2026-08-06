// 코인 지정가 매수·매도 주문 수정(부분 갱신) 요청 DTO — limitPrice·quantity 둘 다 nullable
package com.finplay.api.order.dto.request;

import java.math.BigDecimal;

public record LimitOrderModifyRequest(BigDecimal limitPrice, BigDecimal quantity) {
}
