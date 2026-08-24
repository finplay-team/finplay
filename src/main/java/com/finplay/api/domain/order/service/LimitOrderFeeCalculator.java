// 코인 지정가 예약·체결 금액(원금+수수료) 계산 — 생성·취소·체결·수정 네 서비스가 공유하는 유틸리티
package com.finplay.api.domain.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class LimitOrderFeeCalculator {

	// 매직 넘버 금지 컨벤션 — spec.md 비즈니스 규칙(기존 코인 수수료율 ORD-004와 동일 재사용)
	public static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private LimitOrderFeeCalculator() {}

	// spec.md 비즈니스 규칙: 매수 예약현금 = FLOOR(수량×지정가) + FLOOR(FLOOR(수량×지정가)×0.05%)
	public static Reservation calculate(BigDecimal quantity, BigDecimal limitPrice) {
		long amount = quantity.multiply(limitPrice).setScale(0, RoundingMode.FLOOR).longValueExact();
		long fee = BigDecimal.valueOf(amount).multiply(CRYPTO_FEE_RATE)
			.setScale(0, RoundingMode.FLOOR).longValueExact();
		return new Reservation(amount, fee);
	}

	public record Reservation(long amount, long fee) {
		public long total() {
			return amount + fee;
		}
	}
}
