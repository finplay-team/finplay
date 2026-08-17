// 현재 attempt/run의 불변 BUY·SELL 합계와 최초 SELL, 그리고 이번 실행 매매 결과 수치를 제공하는 order evidence DTO
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;

/**
 * 뒤 네 필드는 이슈 #421에서 더했다 — 진행 조회가 "이번 실습에서 얼마를 벌었는지"를 노출하려면 수량만으로는
 * 부족하고 체결가와 원장 손익이 필요하다. 원장을 읽기만 하며 어떤 값도 새로 저장하지 않는다.
 *
 * @param buyQuantity      현재 run FILLED BUY 수량 합
 * @param sellQuantity     현재 run FILLED SELL 수량 합
 * @param remainingQuantity {@code max(buyQuantity - sellQuantity, 0)}
 * @param firstSellTrade   현재 run의 가장 이른 FILLED SELL. 없으면 {@code null}
 * @param averageBuyPrice  BUY 체결의 <b>수량 가중평균 단가</b>, scale 8 {@code HALF_UP}. 매수가 없으면
 *                         {@code null}. 체결이 1건이면 {@code trade.price}와 정확히 같아
 *                         {@code practice_risk_snapshots.entry_price}(같은 scale·라운딩)와 어긋나지 않는다
 * @param averageSellPrice SELL 체결의 수량 가중평균 단가. 매도 전이면 {@code null}
 * @param realizedPnl      SELL 체결의 {@code trades.realized_pnl} 합. <b>매수·매도 수수료가 모두 반영된</b>
 *                         순손익이다({@code PortfolioSellService.finalizeSellRealizedPnl}). 매도가 없거나
 *                         원장 값이 하나라도 비어 있으면 {@code null}
 * @param soldBuyBasis     팔린 수량에 배분된 매수원가 + 매수수수료 합 — {@code realizedPnl}의 분모다.
 *                         {@code realizedPnl}이 {@code null}이면 함께 {@code null}
 */
public record PracticeRunTradeSummaryDto(
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade firstSellTrade,
	BigDecimal averageBuyPrice,
	BigDecimal averageSellPrice,
	Long realizedPnl,
	Long soldBuyBasis) {
}
