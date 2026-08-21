// LimitOrderFeeCalculator.calculate의 예약금액·수수료 FLOOR 계산을 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LimitOrderFeeCalculatorTest {

	@Test
	void calculateMatchesValueUsedByCreationAndFillServiceTests() {
		// LimitOrderCreationServiceTest·LimitOrderFillServiceTest가 공유하던 값(0.1 * 1,000,000)에 대한 회귀 확인.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("0.1"),
			new BigDecimal("1000000"));

		assertThat(reservation.amount()).isEqualTo(100_000L);
		assertThat(reservation.fee()).isEqualTo(50L);
		assertThat(reservation.total()).isEqualTo(100_050L);
	}

	@Test
	void calculateMatchesValueUsedByCancelServiceSellTest() {
		// LimitOrderCancelServiceTest의 SELL 취소 시나리오(0.1 * 1,000,000)에 대한 회귀 확인.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("0.1"),
			new BigDecimal("1000000"));

		assertThat(reservation.amount()).isEqualTo(100_000L);
		assertThat(reservation.fee()).isEqualTo(50L);
	}

	@Test
	void calculateFloorsAmountToWonWhenQuantityTimesLimitPriceHasFraction() {
		// 0.13 * 999,999 = 129,999.87 -> amount는 원단위로 절사되어 129,999여야 한다.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("0.13"),
			new BigDecimal("999999"));

		assertThat(reservation.amount()).isEqualTo(129_999L);
		// fee = floor(129,999 * 0.0005) = floor(64.9995) = 64
		assertThat(reservation.fee()).isEqualTo(64L);
	}

	@Test
	void calculateFloorsFeeToWonWhenAmountTimesFeeRateHasFraction() {
		// amount 자체는 정수(1 * 100,001)이지만 fee = 100,001 * 0.0005 = 50.0005 -> 50으로 절사되어야 한다.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("1"),
			new BigDecimal("100001"));

		assertThat(reservation.amount()).isEqualTo(100_001L);
		assertThat(reservation.fee()).isEqualTo(50L);
		assertThat(reservation.total()).isEqualTo(100_051L);
	}

	@Test
	void calculateDoesNotFloorFeeWhenAmountTimesFeeRateIsExact() {
		// amount=100,000 -> fee = 100,000 * 0.0005 = 50.0 (절사 경계와 구분되는 정확히 떨어지는 케이스)
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(new BigDecimal("1"),
			new BigDecimal("100000"));

		assertThat(reservation.amount()).isEqualTo(100_000L);
		assertThat(reservation.fee()).isEqualTo(50L);
	}
}
