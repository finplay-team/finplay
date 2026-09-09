package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeRunFillKindDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<Trade, Long>, TradeRepositoryCustom {

	Optional<Trade> findByOrderId(Long orderId);

	@Override
	@EntityGraph(attributePaths = {"account", "account.user"})
	Optional<Trade> findById(Long id);

	@Query("SELECT DISTINCT t.account.id FROM Trade t WHERE t.side = :side AND t.account.market = :market "
		+ "AND t.instrument.tutorialSample = false")
	List<Long> findDistinctAccountIdsBySideAndMarket(
		@Param("side")
		OrderSide side,
		@Param("market")
		Market market);

	boolean existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(Long accountId, OrderSide side);

	boolean existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide side, Market market);

	List<Trade> findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
		Long userId, Long instrumentId, OrderSide side, LocalDateTime after);

	@Query("SELECT t.order.practicePriceSessionId FROM Trade t WHERE t.id = :tradeId")
	Optional<Long> findPracticePriceSessionIdByTradeId(@Param("tradeId")
	Long tradeId);

	@Query("""
		select t from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.domain.order.entity.OrderStatus.FILLED
		order by t.id asc
		""")
	List<Trade> findFilledPracticeRunTrades(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	@Query("""
		select coalesce(sum(case when t.side = com.finplay.api.domain.order.entity.OrderSide.BUY
			then t.quantity else -t.quantity end), 0)
		from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.domain.order.entity.OrderStatus.FILLED
		""")
	BigDecimal sumNetFilledPracticeRunQuantity(@Param("attemptId")
	Long attemptId, @Param("runNumber")
	long runNumber);

	@Query("""
		select new com.finplay.api.domain.order.service.PracticeRunFillKindDto(
			t.order.id, t.side, t.order.orderType)
		from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.domain.order.entity.OrderStatus.FILLED
		""")
	List<PracticeRunFillKindDto> findPracticeRunFillKinds(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	@Query("""
		select max(t.executedAt) from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.domain.order.entity.OrderStatus.FILLED
		  and t.side = com.finplay.api.domain.order.entity.OrderSide.BUY
		""")
	Optional<LocalDateTime> findLatestPracticeRunBuyExecutedAt(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);
}
