package com.finplay.api.domain.portfolio.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SellAllocationSummaryDto(
	BigDecimal buyPrice,
	LocalDateTime earliestBuyAt,
	LocalDate earliestBuySourceTradingDate,
	long allocatedCost,
	long allocatedBuyFee,
	BigDecimal allocatedQuantity,
	List<LocalDate> buySourceTradingDates) {

	public SellAllocationSummaryDto {
		buySourceTradingDates = Collections.unmodifiableList(new ArrayList<>(buySourceTradingDates));
	}
}
