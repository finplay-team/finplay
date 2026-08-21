// 순수 생성기가 반환하는 튜토리얼 일봉 값 객체
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TutorialPriceCandleDto(
	LocalDate date,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	boolean current) {
}
