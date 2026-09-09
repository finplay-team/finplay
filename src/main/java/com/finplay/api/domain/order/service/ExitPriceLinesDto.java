package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

public record ExitPriceLinesDto(BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {
}
