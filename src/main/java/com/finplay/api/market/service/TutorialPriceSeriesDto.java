// 29개 완결 일봉과 현재 일봉 및 canonical close를 함께 반환하는 생성 결과
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.util.List;

public record TutorialPriceSeriesDto(
	List<TutorialPriceCandleDto> candles,
	BigDecimal canonicalPrice) {
	public TutorialPriceSeriesDto {
		candles = List.copyOf(candles);
	}
}
