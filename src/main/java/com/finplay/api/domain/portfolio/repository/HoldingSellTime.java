package com.finplay.api.domain.portfolio.repository;

import java.time.LocalDateTime;

public record HoldingSellTime(Long holdingId, LocalDateTime firstSellExecutedAt) {
}
