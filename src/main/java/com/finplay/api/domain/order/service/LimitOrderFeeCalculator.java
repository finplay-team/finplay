package com.finplay.api.domain.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class LimitOrderFeeCalculator {

	public static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private LimitOrderFeeCalculator() {}

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
