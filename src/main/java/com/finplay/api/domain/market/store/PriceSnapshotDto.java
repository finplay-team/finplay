package com.finplay.api.domain.market.store;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceSnapshotDto(LocalDateTime recordedAt, BigDecimal price) {
}
