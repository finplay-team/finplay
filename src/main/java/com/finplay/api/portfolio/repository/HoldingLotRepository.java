// FIFO 매수 lot 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.HoldingLot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingLotRepository extends JpaRepository<HoldingLot, Long> {

	List<HoldingLot> findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
		Long holdingId, BigDecimal remainingQuantity);

	/**
	 * 특정 종목의 lot을 holding 단위로 묶어, 시점 {@code at} 이전(포함)에 체결된 매수 lot의
	 * {@code original_quantity} 합을 계산한다 (spec 012 §반사실·집단 비교 계산 [집단 비교]).
	 *
	 * <p><b>{@code remaining_quantity}(가변)가 아니라 {@code original_quantity}(불변)를 쓴다.</b>
	 * {@code remaining_quantity}는 현재 상태라 그 뒤 매도로 줄어들므로, 이 값을 쓰면 {@code holdings} 테이블을
	 * 그대로 조회한 것과 같은 문제(과거 시점 스냅샷 부재)가 재현된다.
	 */
	@Query("select new com.finplay.api.portfolio.repository.HoldingQuantitySum("
		+ "lot.holding.id, sum(lot.originalQuantity)) "
		+ "from HoldingLot lot "
		+ "where lot.holding.instrument.id = :instrumentId and lot.executedAt <= :at "
		+ "group by lot.holding.id")
	List<HoldingQuantitySum> sumOriginalQuantityByHoldingForInstrumentAtOrBefore(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);
}
