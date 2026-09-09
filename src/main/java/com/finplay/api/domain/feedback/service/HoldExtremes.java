package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;

record HoldExtremes(
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsHighRate,
	BigDecimal sellVsLowRate,
	HoldHighBasis basis) {

	static HoldExtremes absent() {
		return new HoldExtremes(null, null, null, null, null, null, null);
	}
}
