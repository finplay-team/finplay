// FIFO 매수 lot 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.HoldingLot;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingLotRepository extends JpaRepository<HoldingLot, Long> {

	List<HoldingLot> findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
		Long holdingId, BigDecimal remainingQuantity);
}
