package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

public record PracticeOrderFillContextDto(boolean currentRun, BigDecimal canonicalPrice) {
}
