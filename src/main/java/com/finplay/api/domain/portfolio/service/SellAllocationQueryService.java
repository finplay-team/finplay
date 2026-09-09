package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SellAllocationQueryService {

	private static final int BUY_PRICE_SCALE = 8;

	private final TradeAllocationRepository tradeAllocationRepository;

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

	@Transactional(readOnly = true)
	public List<AllocatedBuyTradeDto> getAllocatedBuyTrades(Long sellTradeId) {
		return tradeAllocationRepository.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(sellTradeId)
			.stream()
			.map(allocation -> {
				HoldingLot lot = allocation.getHoldingLot();
				return new AllocatedBuyTradeDto(lot.getBuyTrade().getId(), lot.getExecutedAt());
			})
			.toList();
	}

	private LocalDate sourceTradingDateOf(HoldingLot lot) {
		Trade buyTrade = lot.getBuyTrade();
		return buyTrade.getStockReplaySession() == null
			? null
			: buyTrade.getStockReplaySession().getSourceTradingDate();
	}
}
