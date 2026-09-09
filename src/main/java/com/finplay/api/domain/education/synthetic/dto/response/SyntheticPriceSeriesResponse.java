package com.finplay.api.domain.education.synthetic.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record SyntheticPriceSeriesResponse(String title, Integer tickSeconds, List<BigDecimal> prices) {

	public SyntheticPriceSeriesResponse {
		prices = List.copyOf(prices);
	}
}
