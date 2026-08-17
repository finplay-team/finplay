// 튜토리얼 이번 실행의 수익률과 매도 위치 판정을 계산하는 순수 계산기(어떤 것도 저장하지 않는다)
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeSellVerdict;
import com.finplay.api.education.marketpractice.dto.response.PracticeTradeResultResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 이슈 #421. 체결가·실현손익은 원장이 이미 가진 값이라 {@code TradeService.summarizePracticeRun}이 집계해
 * 넘겨주고, 여기서는 <b>그 값들로부터 파생되는 두 가지</b>만 만든다 — 수익률과 매도 위치 판정.
 *
 * <p><b>판정을 클라이언트에 넘기지 않는다.</b> 손절 -3%·익절 +5% 기준선은 서버가 만든 교육용 규칙이고
 * (039 TUTORIAL-FLOW-008), 경계 포함 여부 같은 세부 규칙이 프론트에 복제되면 한쪽만 바뀌어도 예외도 로그도
 * 없이 다른 문구가 나간다 — {@link EvidenceJudgmentService}가 관찰 evidence 판정을 서버에 둔 것과 같은
 * 이유다.
 *
 * <p>{@code PostSellArithmetic}(feedback)의 같은 이름 계산과 값이 같지만 그쪽은 패키지 전용이고 다른
 * 도메인이라 재사용하지 않고 식·정밀도만 맞춘다.
 */
final class PracticeTradeResultCalculator {

	// PostSellArithmetic.RETURN_RATE_SCALE·PortfolioService의 수익률과 같은 값이다.
	private static final int RETURN_RATE_SCALE = 4;

	private PracticeTradeResultCalculator() {}

	static PracticeTradeResultResponse calculate(
		BigDecimal buyPrice,
		BigDecimal sellPrice,
		Long realizedPnl,
		Long soldBuyBasis,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice) {
		PracticeSellVerdict verdict = judgeSellVerdict(sellPrice, stopLossPrice, takeProfitPrice);
		return new PracticeTradeResultResponse(
			buyPrice,
			sellPrice,
			realizedPnl,
			returnRate(realizedPnl, soldBuyBasis),
			verdict == null ? null : verdict.name());
	}

	/**
	 * {@code realizedPnl ÷ soldBuyBasis}, scale 4 {@code HALF_UP}.
	 *
	 * <p>분모가 0 이하이거나 실현손익이 없으면 {@code null}이다 — 그런 매도는 원장에 생기지 않지만
	 * {@code ArithmeticException}으로 진행 조회 전체가 500이 되는 것보다 낫다. 값을 0으로 채우지 않는 것은
	 * "본전"과 "계산할 수 없음"이 화면에서 같은 문구가 되면 안 되기 때문이다.
	 */
	private static BigDecimal returnRate(Long realizedPnl, Long soldBuyBasis) {
		if (realizedPnl == null || soldBuyBasis == null || soldBuyBasis <= 0L) {
			return null;
		}
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(soldBuyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 매도가가 익절선 <b>이상</b>이면 {@code ABOVE_TAKE_PROFIT}, 손절선 <b>이하</b>면
	 * {@code BELOW_STOP_LOSS}, 그 사이면 {@code BETWEEN_LINES}다.
	 *
	 * <p><b>양 끝을 포함하고 익절선을 먼저 본다.</b> 정확히 기준선에서 팔린 경우를 "사이"로 밀면 사용자가
	 * 화면에서 본 선을 그대로 맞혔을 때 오히려 아무 판정도 못 받는다.
	 * {@code stopLossPrice < entryPrice < takeProfitPrice}(0.97·1.05)가 유지되는 한 두 조건이 동시에 참일 수
	 * 없어 순서가 값을 바꾸지 않지만, 그 불변조건이 깨진 입력에서도 판정이 흔들리지 않게 순서를 못박아 둔다.
	 *
	 * @return 매도 전이거나 기준선이 없으면 {@code null}
	 */
	private static PracticeSellVerdict judgeSellVerdict(
		BigDecimal sellPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {
		if (sellPrice == null || stopLossPrice == null || takeProfitPrice == null) {
			return null;
		}
		if (sellPrice.compareTo(takeProfitPrice) >= 0) {
			return PracticeSellVerdict.ABOVE_TAKE_PROFIT;
		}
		if (sellPrice.compareTo(stopLossPrice) <= 0) {
			return PracticeSellVerdict.BELOW_STOP_LOSS;
		}
		return PracticeSellVerdict.BETWEEN_LINES;
	}
}
