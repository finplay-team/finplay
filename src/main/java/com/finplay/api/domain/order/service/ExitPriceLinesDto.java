// 확정된 OCO 실행 가격선(손절가·익절가) snapshot — 트리거 판정은 이 두 값만 사용한다
package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

public record ExitPriceLinesDto(BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {
}
