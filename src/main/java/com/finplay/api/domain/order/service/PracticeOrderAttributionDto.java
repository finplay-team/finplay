package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

public record PracticeOrderAttributionDto(Long attemptId, long runNumber, BigDecimal canonicalPrice) {
}
