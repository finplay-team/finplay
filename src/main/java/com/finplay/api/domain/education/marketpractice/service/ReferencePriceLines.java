package com.finplay.api.domain.education.marketpractice.service;

import java.math.BigDecimal;

public record ReferencePriceLines(BigDecimal referenceStopLossPrice, BigDecimal referenceTakeProfitPrice) {
}
