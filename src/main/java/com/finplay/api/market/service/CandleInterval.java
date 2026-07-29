// 캔들 API의 interval 쿼리 파라미터를 검증하는 열거형 — 1차 범위는 1분봉(1m)만 허용한다.
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.util.Arrays;

public enum CandleInterval {

	ONE_MINUTE("1m");

	private final String value;

	CandleInterval(String value) {
		this.value = value;
	}

	public static CandleInterval from(String value) {
		return Arrays.stream(values())
			.filter(candleInterval -> candleInterval.value.equals(value))
			.findFirst()
			.orElseThrow(() -> new BusinessException(
				ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다. interval=1m만 지원합니다."));
	}
}
