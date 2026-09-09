package com.finplay.api.domain.portfolio.repository;

import com.finplay.api.domain.portfolio.entity.HoldingLot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingLotRepository extends JpaRepository<HoldingLot, Long> {

	List<HoldingLot> findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
		Long holdingId, BigDecimal remainingQuantity);

	List<HoldingLot> findByHoldingIdIn(List<Long> holdingIds);

	@Query("select new com.finplay.api.domain.portfolio.repository.HoldingQuantitySum("
		+ "lot.holding.id, sum(lot.originalQuantity)) "
		+ "from HoldingLot lot "
		+ "where lot.holding.instrument.id = :instrumentId and lot.executedAt <= :at "
		+ "group by lot.holding.id")
	List<HoldingQuantitySum> sumOriginalQuantityByHoldingForInstrumentAtOrBefore(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);
}
