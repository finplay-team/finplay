package com.finplay.api.domain.market.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.Arrays;

public enum CandleInterval {

	ONE_MINUTE("1m"),
	ONE_DAY("1d"),
	ONE_WEEK("1w"),
	ONE_MONTH("1M");

	private final String value;

	CandleInterval(String value) {
		this.value = value;
	}

	public static CandleInterval from(String value) {
		return Arrays.stream(values())
			.filter(candleInterval -> candleInterval.value.equals(value))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
				"지원하지 않는 캔들 간격입니다. interval=1m, 1d, 1w, 1M 중 하나여야 합니다."));
	}

	public boolean isAggregated() {
		return this != ONE_MINUTE;
	}
}
