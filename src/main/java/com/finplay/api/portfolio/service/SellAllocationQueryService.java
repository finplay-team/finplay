// 매도 체결 1건의 FIFO 배분·lot을 읽어 회고용 수치로 요약하는 읽기 전용 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec 012 §C-6이 {@code portfolio}에 요구한 "배분·lot 조회"다 — {@code feedback}의 매도 회고(FEED-007)가 쓴다.
 * 집단 비교의 모집단 재구성은 {@code plan.md} 7번이 이 패키지에 따로 더한다.
 *
 * <p><b>쓰지 않는다.</b> 원장(주문·체결·계좌·보유·lot·배분)은 이 spec의 어떤 코드도 바꾸지 않는다 — 매도 회고가
 * 쓰는 테이블은 {@code trade_feedbacks} 하나뿐이다.
 */
@Service
@RequiredArgsConstructor
public class SellAllocationQueryService {

	// Holding.averagePrice와 같은 정밀도. 단가 컬럼(holding_lots.unit_cost·trades.price)의 scale이 8이다.
	private static final int BUY_PRICE_SCALE = 8;

	private final TradeAllocationRepository tradeAllocationRepository;

	/**
	 * 매도 체결 1건의 배분을 요약한다.
	 *
	 * @param sellTradeId 매도 체결 id. 소유권 검증은 호출부(체결 조회)가 이미 끝냈다고 본다
	 * @return 가중평균 매수단가·가장 이른 매수 시각·배분 합계와 lot별 원본 거래일
	 * @throws IllegalStateException 배분이 0건이면 원장 불일치다 — 매도 체결은 항상 lot을 소비하며
	 *     ({@code PortfolioSellService.applySellTrade}) 잔여수량이 남으면 그 자리에서 이미 거부된다. 여기서
	 *     빈 요약을 돌려주면 매수단가가 0으로 나누어지거나 조용히 {@code null}이 된다
	 */
	@Transactional(readOnly = true)
	public SellAllocationSummaryDto getSellAllocationSummary(Long sellTradeId) {
		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(sellTradeId);
		if (allocations.isEmpty()) {
			throw new IllegalStateException("매도 체결에 배분된 lot이 없습니다. sellTradeId=" + sellTradeId);
		}

		long allocatedCost = 0L;
		long allocatedBuyFee = 0L;
		BigDecimal allocatedQuantity = BigDecimal.ZERO;
		List<LocalDate> buySourceTradingDates = new ArrayList<>();

		for (TradeAllocation allocation : allocations) {
			allocatedCost += allocation.getAllocatedCost();
			allocatedBuyFee += allocation.getAllocatedBuyFee();
			allocatedQuantity = allocatedQuantity.add(allocation.getAllocatedQuantity());
			buySourceTradingDates.add(sourceTradingDateOf(allocation.getHoldingLot()));
		}

		// 정렬이 lot 체결시각 오름차순이라 첫 배분의 lot이 가장 이른 매수다 (FEED-007 — 가장 이른 executed_at).
		HoldingLot earliestLot = allocations.get(0).getHoldingLot();
		BigDecimal buyPrice = BigDecimal.valueOf(allocatedCost)
			.divide(allocatedQuantity, BUY_PRICE_SCALE, RoundingMode.HALF_UP);

		return new SellAllocationSummaryDto(
			buyPrice,
			earliestLot.getExecutedAt(),
			sourceTradingDateOf(earliestLot),
			allocatedCost,
			allocatedBuyFee,
			allocatedQuantity,
			buySourceTradingDates);
	}

	// 코인 체결은 재생세션이 없어 null이다 (Trade가 그것을 강제한다).
	private LocalDate sourceTradingDateOf(HoldingLot lot) {
		Trade buyTrade = lot.getBuyTrade();
		return buyTrade.getStockReplaySession() == null
			? null
			: buyTrade.getStockReplaySession().getSourceTradingDate();
	}
}
