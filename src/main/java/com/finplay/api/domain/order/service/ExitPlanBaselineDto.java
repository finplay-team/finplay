package com.finplay.api.domain.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExitPlanBaselineDto(BigDecimal price, LocalDateTime observedAt) {
}
