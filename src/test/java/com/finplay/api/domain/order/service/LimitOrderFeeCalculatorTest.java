package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LimitOrderFeeCalculatorTest {

	@Test
	void calculateMatchesValueUsedByCreationAndFillServiceTests() {
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("0.1"),
			new BigDecimal("1000000"));

		assertThat(reservation.amount()).isEqualTo(100_000L);
		assertThat(reservation.fee()).isEqualTo(50L);
		assertThat(reservation.total()).isEqualTo(100_050L);
	}

	@Test
	void calculateFloorsAmountToWonWhenQuantityTimesLimitPriceHasFraction() {
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("0.13"),
			new BigDecimal("999999"));

		assertThat(reservation.amount()).isEqualTo(129_999L);
		assertThat(reservation.fee()).isEqualTo(64L);
	}

	@Test
	void calculateFloorsFeeToWonWhenAmountTimesFeeRateHasFraction() {
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("1"),
			new BigDecimal("100001"));

		assertThat(reservation.amount()).isEqualTo(100_001L);
		assertThat(reservation.fee()).isEqualTo(50L);
		assertThat(reservation.total()).isEqualTo(100_051L);
	}

	@Test
	void calculateDoesNotFloorFeeWhenAmountTimesFeeRateIsExact() {
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("1"),
			new BigDecimal("100000"));

		assertThat(reservation.amount()).isEqualTo(100_000L);
		assertThat(reservation.fee()).isEqualTo(50L);
	}
}
