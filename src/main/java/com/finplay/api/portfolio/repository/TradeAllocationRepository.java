// 매도 체결의 lot별 배분 내역 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.TradeAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeAllocationRepository extends JpaRepository<TradeAllocation, Long> {

	@Query("select coalesce(sum(a.allocatedCost), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
	long sumAllocatedCostByHoldingLotId(@Param("holdingLotId")
	Long holdingLotId);

	@Query("select coalesce(sum(a.allocatedBuyFee), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
	long sumAllocatedBuyFeeByHoldingLotId(@Param("holdingLotId")
	Long holdingLotId);
}
