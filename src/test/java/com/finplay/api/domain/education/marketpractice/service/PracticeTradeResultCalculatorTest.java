// 이슈 #421 진행 조회의 이번 실행 매매 결과(수익률·매도 위치 판정) 계산을 검증하는 단위 테스트다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTradeResultResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PracticeTradeResultCalculatorTest {

	// 039 TUTORIAL-FLOW-008의 -3%·+5% 기준선을 entryPrice 100 기준으로 그대로 쓴다.
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
		// 매수 전이라도 계산기는 객체 자체는 만든다 — evidence가 tradeResult를 null로 만드는 건 상위(빈 evidence)
		// 책임이라 여기서 null을 반환하면 계약이 두 곳으로 갈라진다.
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
		// 경계 포함 규칙. 사용자가 화면에서 본 익절선을 정확히 맞혔을 때 BETWEEN_LINES로 밀리면 안 된다.
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
		// 기준선은 scale 8로 저장되고 평균 매도가도 scale 8로 나오지만, 판정이 equals가 아니라 compareTo 기반인지
		// 확인한다 — equals면 "105" 와 "105.00000000"이 다른 값이 돼 경계에서만 판정이 조용히 뒤집힌다.
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
		// 익절선 우선 규칙. stopLossPrice < takeProfitPrice 불변조건이 깨진 입력(선이 뒤집혀 두 조건이 동시에
		// 참인 경우)에서도 ABOVE_TAKE_PROFIT이 나와야 순서가 못박혀 있다고 말할 수 있다.
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
		// 판정이 없어도 손익·수익률은 그대로 나가야 한다 — 두 계산은 서로 독립이다.
		assertThat(noStopLoss.realizedPnl()).isEqualTo(4_000L);
		assertThat(noStopLoss.returnRate()).isEqualByComparingTo(new BigDecimal("0.0400"));
	}

	@Test
	void calculateDividesRealizedPnlBySoldBuyBasisAtScaleFour() {
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_985L, 1_000_150L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.realizedPnl()).isEqualTo(4_985L);
		// 4985 / 1000150 = 0.0049842... → scale 4 HALF_UP = 0.0050
		assertThat(result.returnRate()).isEqualByComparingTo(new BigDecimal("0.0050"));
		assertThat(result.returnRate().scale()).isEqualTo(4);
	}

	@Test
	void calculateRoundsReturnRateHalfUpAwayFromZeroOnExactHalf() {
		// 1/32 = 0.03125 — HALF_UP이면 0.0313, HALF_DOWN·HALF_EVEN이면 0.0312다. 음수도 0에서 멀어지는 쪽으로
		// 반올림되는지 함께 본다(HALF_UP과 CEILING/FLOOR를 가르는 자리다).
		PracticeTradeResultResponse gain = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("103.00000000"), 1L, 32L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);
		PracticeTradeResultResponse loss = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("98.00000000"), -1L, 32L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(gain.returnRate()).isEqualByComparingTo(new BigDecimal("0.0313"));
		assertThat(loss.returnRate()).isEqualByComparingTo(new BigDecimal("-0.0313"));
	}

	@Test
	void calculateReturnsZeroRateWithoutConfusingItWithMissingRate() {
		// 손익이 정확히 0인 "본전" 매도는 0.0000이어야 한다 — 계산 불가(null)와 화면 문구가 같아지면 안 된다.
		PracticeTradeResultResponse result = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("100.00000000"), 0L, 1_000_000L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.returnRate()).isNotNull();
		assertThat(result.sellVerdict()).isEqualTo("BETWEEN_LINES");
	}

	@Test
	void calculateReturnsNullRateInsteadOfDividingByZeroBasis() {
		// 분모 역산(buyBasis = (amount - fee) - realizedPnl)이 0이 되는 입력에서 ArithmeticException이 나면 진행
		// 조회 전체가 500이 된다. 0 이하 분모는 예외 없이 null로 떨어져야 한다.
		assertThatCode(() -> PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, 0L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null))
			.doesNotThrowAnyException();

		PracticeTradeResultResponse zeroBasis = PracticeTradeResultCalculator.calculate(
			ENTRY_PRICE, new BigDecimal("105.00000000"), 4_000L, 0L, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, null);

		assertThat(zeroBasis.returnRate()).isNull();
		// 분모만 못 구했을 뿐 손익·판정은 원장 값 그대로 나가야 한다.
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
		// 손익이 비어도 매도 자체는 일어났으므로 매도가와 판정은 남는다.
		assertThat(result.sellPrice()).isEqualByComparingTo(new BigDecimal("105.00000000"));
		assertThat(result.sellVerdict()).isEqualTo("ABOVE_TAKE_PROFIT");
	}

	// 042 EXITPRESET-008 — sellVerdict와 sellCause는 다른 질문에 답한다. 자동 예약이 팔면 verdict는 정의상
	// 항상 경계값이 되므로, 그것만으로는 "예약이 팔았는지 내가 팔았는지"를 구분할 수 없다.
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

		// 같은 체결가라 verdict는 같지만 원인은 다르다 — 두 값이 서로를 대체할 수 없다는 뜻이다.
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
		// buyPrice·sellPrice는 계산 대상이 아니라 원장 집계값의 통과다 — 여기서 setScale이 끼면
		// riskSnapshot.entryPrice와 어긋난다.
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
