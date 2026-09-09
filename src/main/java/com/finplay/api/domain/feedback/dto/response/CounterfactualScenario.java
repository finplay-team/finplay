package com.finplay.api.domain.feedback.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CounterfactualScenario(BigDecimal price, LocalDateTime at, BigDecimal returnRate) {
}
