package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.service.PracticeOrderFillAttributionDto;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {

	@Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.user.id = :userId AND o.idempotencyKey = :idempotencyKey")
	Optional<Order> findByUserIdAndIdempotencyKey(
		@Param("userId")
		Long userId, @Param("idempotencyKey")
		String idempotencyKey);

	List<Order> findByAccountId(Long accountId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id")
	Long id);

	@Query("""
		select new com.finplay.api.domain.order.service.PracticeOrderFillAttributionDto(
			o.practiceAttemptId, o.practiceAttemptRunNumber, o.user.id, o.instrument.id)
		from Order o
		where o.id = :id and o.practiceAttemptId is not null
		""")
	Optional<PracticeOrderFillAttributionDto> findPracticeFillAttribution(@Param("id")
	Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o from Order o
		where o.practiceAttemptId = :attemptId
		  and o.practiceAttemptRunNumber = :runNumber
		order by o.id asc
		""")
	List<Order> findPracticeRunOrdersForUpdate(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	@Query("""
		select o from Order o
		join fetch o.instrument
		where o.practiceAttemptId = :attemptId
		  and o.practiceAttemptRunNumber = :runNumber
		order by o.id asc
		""")
	List<Order> findPracticeRunOrders(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	@Query("""
		select o from Order o
		where o.instrument.id = :instrumentId and o.status = com.finplay.api.domain.order.entity.OrderStatus.PENDING
		  and o.orderType = com.finplay.api.domain.order.entity.OrderType.LIMIT
		  and o.practicePriceSessionId is null
		  and o.practiceAttemptId is null
		  and ((o.side = com.finplay.api.domain.order.entity.OrderSide.BUY and o.limitPrice >= :price)
		    or (o.side = com.finplay.api.domain.order.entity.OrderSide.SELL and o.limitPrice <= :price))
		order by o.requestedAt asc, o.id asc
		""")
	List<Order> findPendingLimitOrdersToFill(@Param("instrumentId")
	Long instrumentId, @Param("price")
	BigDecimal price);

	boolean existsByPracticePriceSessionIdAndStatus(Long practicePriceSessionId, OrderStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o from Order o
		where o.practicePriceSessionId = :sessionId and o.status = com.finplay.api.domain.order.entity.OrderStatus.PENDING
		order by o.id asc
		""")
	List<Order> findPendingBySessionIdForUpdate(@Param("sessionId")
	Long sessionId);

	@Query("""
		select o.id from Order o
		where o.practicePriceSessionId = :sessionId
		  and o.status = com.finplay.api.domain.order.entity.OrderStatus.PENDING
		order by o.id asc
		""")
	List<Long> findPendingIdsBySessionId(@Param("sessionId")
	Long sessionId);

	@Query("""
		select o.id from Order o
		where o.practiceAttemptId = :attemptId
		  and o.practiceAttemptRunNumber = :runNumber
		  and o.status = com.finplay.api.domain.order.entity.OrderStatus.PENDING
		  and o.orderType = com.finplay.api.domain.order.entity.OrderType.LIMIT
		order by o.id asc
		""")
	List<Long> findPendingPracticeRunOrderIds(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id IN :ids ORDER BY o.id ASC")
	List<Order> findByIdInForUpdate(@Param("ids")
	List<Long> ids);
}
