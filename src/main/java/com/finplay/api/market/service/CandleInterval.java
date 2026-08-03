// 캔들 API의 interval 쿼리 파라미터를 검증하는 열거형 — 1분봉(1m)·일봉(1d)·주봉(1w)·월봉(1M) 4값을 허용한다(이슈 #143).
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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

	// 대소문자를 구분해 정확히 일치할 때만 매칭한다 — equalsIgnoreCase로 바꾸면 월봉(1M)과 분봉(1m)이 같은 값으로
	// 충돌한다. spec 확정 계약(docs/specs/013-candle-interval/spec.md "공통 계약"): 서버는 대소문자를 정규화하지 않는다.
	public static CandleInterval from(String value) {
		return Arrays.stream(values())
			.filter(candleInterval -> candleInterval.value.equals(value))
			.findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
				"지원하지 않는 캔들 간격입니다. interval=1m, 1d, 1w, 1M 중 하나여야 합니다."));
	}

	// 주식 집계(일·주·월봉) 대상인지 판정한다 — 1분봉은 stock_candles를 그대로 조회하고, 나머지는 분봉을 묶어 집계한다.
	public boolean isAggregated() {
		return this != ONE_MINUTE;
	}
}
