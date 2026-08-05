// 매도 체결의 lot별 배분 내역 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.TradeAllocation;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeAllocationRepository extends JpaRepository<TradeAllocation, Long> {

	/**
	 * 매도 체결 1건이 소비한 배분을 lot의 체결시각 오름차순 + lot id 오름차순으로 읽는다 — 배분 시점의 FIFO
	 * 순서({@code PortfolioSellService})와 같은 정렬이다.
	 *
	 * <p><b>2차 키({@code lot.id})를 지우지 않는다.</b> 같은 시각에 체결된 lot이 둘 이상이면 정렬이 실행마다
	 * 달라지는데, InnoDB가 흔히 PK 순서로 돌려주어 테스트가 우연히 통과한다. 첫 행의 {@code executedAt}이
	 * 매도 회고의 {@code buyAt}(배분된 lot 중 <b>가장 이른</b> 체결 시각, spec FEED-007)이 되므로 순서가 곧
	 * 값이다.
	 *
	 * <p>lot과 그 매수 체결·재생세션을 함께 읽는다 — 호출부가 lot별 원본 거래일을 봐야 해서(매도 회고의
	 * {@code sameSessionCompleted} 판정) 지연 로딩으로 두면 배분 수만큼 쿼리가 늘어난다.
	 */
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

	/**
	 * 특정 종목의 배분을 holding 단위로 묶어, <b>그 배분이 속한 매도 체결</b>의 {@code executed_at}이 시점
	 * {@code at} 이전(포함)인 {@code allocated_quantity} 합을 계산한다 (spec 012 §반사실·집단 비교 계산
	 * [집단 비교]).
	 *
	 * <p>매도 시각의 정본은 {@code trade_allocations.created_at}이 아니라 <b>그 배분이 속한 매도 체결의
	 * {@code executed_at}</b>이다 — {@code holderPopulationQueryService}가 이 합을
	 * {@link HoldingLotRepository#sumOriginalQuantityByHoldingForInstrumentAtOrBefore}의 매수 합에서 빼서 시점
	 * {@code at}의 순보유수량을 구한다.
	 */
	@Query("select new com.finplay.api.portfolio.repository.HoldingQuantitySum("
		+ "a.holdingLot.holding.id, sum(a.allocatedQuantity)) "
		+ "from TradeAllocation a "
		+ "where a.holdingLot.holding.instrument.id = :instrumentId and a.sellTrade.executedAt <= :at "
		+ "group by a.holdingLot.holding.id")
	List<HoldingQuantitySum> sumAllocatedQuantityByHoldingForInstrumentAtOrBefore(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);

	/**
	 * 특정 종목의 배분을 holding 단위로 묶어, <b>그 배분이 속한 매도 체결</b>의 {@code executed_at}이 시점
	 * {@code at} 뒤(초과)인 것 중 가장 이른 시각을 계산한다 (spec 012 §반사실·집단 비교 계산 [집단 비교]).
	 *
	 * <p>{@code HolderPopulationQueryService}가 이 값을 T 시점 모집단(위 두 조회로 계산)과 교차해 "매도까지
	 * 걸린 분"을 구한다. T 이전(포함)에 판 매도는 모집단 계산이 이미 순보유수량에서 뺐으므로, 여기서는
	 * {@code at} <b>초과</b>만 본다 — 같은 시각이 두 조회에서 겹쳐 이중으로 잡히지 않는다.
	 */
	@Query("select new com.finplay.api.portfolio.repository.HoldingSellTime("
		+ "a.holdingLot.holding.id, min(a.sellTrade.executedAt)) "
		+ "from TradeAllocation a "
		+ "where a.holdingLot.holding.instrument.id = :instrumentId and a.sellTrade.executedAt > :at "
		+ "group by a.holdingLot.holding.id")
	List<HoldingSellTime> findFirstSellExecutedAtByHoldingForInstrumentAfter(
		@Param("instrumentId")
		Long instrumentId, @Param("at")
		LocalDateTime at);
}
