package com.finplay.api.domain.portfolio.repository;

import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeAllocationRepository extends JpaRepository<TradeAllocation, Long> {

	@Query("select a from TradeAllocation a "
		+ "join fetch a.holdingLot lot "
		+ "join fetch lot.buyTrade buyTrade "
		+ "left join fetch buyTrade.stockReplaySession "
		+ "where a.sellTrade.id = :sellTradeId "
		+ "order by lot.executedAt asc, lot.id asc")
	List<TradeAllocation> findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(@Param("sellTradeId")
	Long sellTradeId);

	@Query("select coalesce(sum(a.allocatedCost), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
	long sumAllocatedCostByHoldingLotId(@Param("holdingLotId")
	Long holdingLotId);

	@Query("select coalesce(sum(a.allocatedBuyFee), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
	long sumAllocatedBuyFeeByHoldingLotId(@Param("holdingLotId")
	Long holdingLotId);

	@Query("select new com.finplay.api.domain.portfolio.repository.HoldingQuantitySum("
		+ "a.holdingLot.holding.id, sum(a.allocatedQuantity)) "
		+ "from TradeAllocation a "
		+ "where a.holdingLot.holding.instrument.id = :instrumentId and a.sellTrade.executedAt <= :at "
		+ "group by a.holdingLot.holding.id")
	List<HoldingQuantitySum> sumAllocatedQuantityByHoldingForInstrumentAtOrBefore(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);

	@Query("select new com.finplay.api.domain.portfolio.repository.HoldingSellTime("
		+ "a.holdingLot.holding.id, min(a.sellTrade.executedAt)) "
		+ "from TradeAllocation a "
		+ "where a.holdingLot.holding.instrument.id = :instrumentId and a.sellTrade.executedAt > :at "
		+ "group by a.holdingLot.holding.id")
	List<HoldingSellTime> findFirstSellExecutedAtByHoldingForInstrumentAfter(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);
}
