package com.finplay.api.domain.portfolio.service;

import java.time.LocalDateTime;

public record AllocatedBuyTradeDto(Long buyTradeId, LocalDateTime executedAt) {
}
