package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTradeResultResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PracticeTradeResultCalculatorTest {

	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100.00000000");
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("97.00000000");
	private static final BigDecimal TAKE_PROFIT_PRICE = new BigDecimal("105.00000000");

	@Test
	void calculateFillsOnlyBuyPriceBeforeAnySell() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, null, null, null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result).isNotNull();
		assertThat(result.buyPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(result.sellPrice()).isNull();
		assertThat(result.realizedPnl()).isNull();
		assertThat(result.returnRate()).isNull();
		assertThat(result.sellVerdict()).isNull();
	}

	@Test
	void calculateReturnsAllNullFieldsWhenNothingHasBeenTradedYet() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			null, null, null, null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result).isNotNull();
		assertThat(result.buyPrice()).isNull();
		assertThat(result.sellPrice()).isNull();
		assertThat(result.realizedPnl()).isNull();
		assertThat(result.returnRate()).isNull();
		assertThat(result.sellVerdict()).isNull();
	}

	@Test
	void calculateJudgesAboveTakeProfitWhenSellPriceExactlyEqualsTakeProfitLine() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
	}

	@Test
	void calculateJudgesBelowStopLossWhenSellPriceExactlyEqualsStopLossLine() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("97.00000000"), -3_000L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.sellVerdict()).isEqualTo("BELOW_STOP_LOSS");
	}

	@Test
	void calculateJudgesBoundaryLinesByValueNotByScale() {
		PracticeTradeResultResponse atTakeProfit = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105"), 4_000L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);
		PracticeTradeResultResponse atStopLoss = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("97"), -3_000L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(atTakeProfit.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
		assertThat(atStopLoss.sellVerdict()).isEqualTo("BELOW_STOP_LOSS");
	}

	@Test
	void calculateJudgesBetweenLinesJustInsideBothBoundaries() {
		PracticeTradeResultResponse justUnderTakeProfit = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("104.99999999"), 3_900L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);
		PracticeTradeResultResponse justAboveStopLoss = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("97.00000001"), -2_900L, 100_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(justUnderTakeProfit.sellVerdict()).isEqualTo("BETWEEN_LINES");
		assertThat(justAboveStopLoss.sellVerdict()).isEqualTo("BETWEEN_LINES");
	}

	@Test
	void calculatePrefersTakeProfitVerdictWhenBothBoundaryConditionsHold() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("100.00000000"), 0L, 100_000L,
			new BigDecimal("105.00000000"), new BigDecimal("97.00000000"), null);

		assertThat(result.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
	}

	@Test
	void calculateLeavesVerdictNullWhenRiskLinesAreMissing() {
		PracticeTradeResultResponse noStopLoss = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("106.00000000"), 4_000L, 100_000L, null, TAKE_PROFIT_PRICE, null);
		PracticeTradeResultResponse noTakeProfit = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("106.00000000"), 4_000L, 100_000L, STOP_LOSS_PRICE, null, null);

		assertThat(noStopLoss.sellVerdict()).isNull();
		assertThat(noTakeProfit.sellVerdict()).isNull();
		assertThat(noStopLoss.realizedPnl()).isEqualTo(4_000L);
		assertThat(noStopLoss.returnRate()).isEqualByComparingTo(new BigDecimal("0.0400"));
	}

	@Test
	void calculateDividesRealizedPnlBySoldBuyBasisAtScaleFour() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_985L, 1_000_150L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.realizedPnl()).isEqualTo(4_985L);
		assertThat(result.returnRate()).isEqualByComparingTo(new BigDecimal("0.0050"));
		assertThat(result.returnRate().scale()).isEqualTo(4);
	}

	@Test
	void calculateRoundsReturnRateHalfUpAwayFromZeroOnExactHalf() {
		PracticeTradeResultResponse gain = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("103.00000000"), 1L, 32L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);
		PracticeTradeResultResponse loss = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("98.00000000"), -1L, 32L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(gain.returnRate()).isEqualByComparingTo(new BigDecimal("0.0313"));
		assertThat(loss.returnRate()).isEqualByComparingTo(new BigDecimal("-0.0313"));
	}

	@Test
	void calculateReturnsZeroRateWithoutConfusingItWithMissingRate() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("100.00000000"), 0L, 1_000_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.returnRate()).isNotNull();
		assertThat(result.sellVerdict()).isEqualTo("BETWEEN_LINES");
	}

	@Test
	void calculateReturnsNullRateInsteadOfDividingByZeroBasis() {
		assertThatCode(() -> PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, 0L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null))
			.doesNotThrowAnyException();

		PracticeTradeResultResponse zeroBasis = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, 0L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(zeroBasis.returnRate()).isNull();
		assertThat(zeroBasis.realizedPnl()).isEqualTo(4_000L);
		assertThat(zeroBasis.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
	}

	@Test
	void calculateReturnsNullRateWhenBasisIsNegative() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, -1L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.returnRate()).isNull();
		assertThat(result.realizedPnl()).isEqualTo(4_000L);
	}

	@Test
	void calculateReturnsNullRateWhenLedgerRealizedPnlIsMissing() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), null, 1_000_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.realizedPnl()).isNull();
		assertThat(result.returnRate()).isNull();
		assertThat(result.sellPrice()).isEqualByComparingTo(new BigDecimal("105.00000000"));
		assertThat(result.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
	}

	@Test
	void sellCauseIsCarriedThroughAndIsIndependentOfSellVerdict() {
		BigDecimal stopLoss = new BigDecimal("97");
		BigDecimal takeProfit = new BigDecimal("105");

		PracticeTradeResultResponse automatic = PracticeTradeResultCalculator.calculate(
			new BigDecimal("100"), new BigDecimal("97"), -3_000L, 100_000L, stopLoss, takeProfit,
			PracticeSellCause.STOP_LOSS);
		PracticeTradeResultResponse manual = PracticeTradeResultCalculator.calculate(
			new BigDecimal("100"), new BigDecimal("97"), -3_000L, 100_000L, stopLoss, takeProfit,
			PracticeSellCause.MANUAL);

		assertThat(automatic.sellVerdict()).isEqualTo(manual.sellVerdict());
		assertThat(automatic.sellCause()).isEqualTo("STOP_LOSS");
		assertThat(manual.sellCause()).isEqualTo("MANUAL");
	}

	@Test
	void sellCauseIsNullBeforeAnySell() {
		PracticeTradeResultResponse beforeSell = PracticeTradeResultCalculator.calculate(
			new BigDecimal("100"), null, null, null, new BigDecimal("97"), new BigDecimal("105"), null);

		assertThat(beforeSell.sellCause()).isNull();
	}

	@Test
	void calculatePassesLedgerPricesThroughWithoutRescalingThem() {
		BigDecimal buyPrice = new BigDecimal("10932.45600000");
		BigDecimal sellPrice = new BigDecimal("11000.00000000");
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			buyPrice, sellPrice, 5_000L, 100_000L,
			new BigDecimal("10604.48232000"), new BigDecimal("11479.07880000"), null);

		assertThat(result.buyPrice()).isSameAs(buyPrice);
		assertThat(result.sellPrice()).isSameAs(sellPrice);
		assertThat(result.sellVerdict()).isEqualTo("BETWEEN_LINES");
	}
}
