package com.finplay.api.domain.order.service;

import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeRunRestartCommand(
	Long attemptId,
	long runNumber,
	Long userId,
	Market market,
	Long instrumentId,
	BigDecimal canonicalPrice,
	LocalDateTime restartedAt) {
}
