package com.finplay.api.domain.portfolio.repository;

import java.math.BigDecimal;

public record HoldingQuantitySum(Long holdingId, BigDecimal quantity) {
}
