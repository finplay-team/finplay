// 주문 요청 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
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

	// 지정가 체결 시 주문 락(015-limit-order LMT-002, 중복 체결 이벤트 방지)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	Optional<Order> findByIdForUpdate(@Param("id")
	Long id);

	// 가격 갱신 시 체결 후보 지정가 주문 조회(015-limit-order LMT-002) — idx_orders_limit_fill 인덱스 활용
	@Query("""
		select o from Order o
		where o.instrument.id = :instrumentId and o.status = com.finplay.api.order.domain.OrderStatus.PENDING
		  and o.orderType = com.finplay.api.order.domain.OrderType.LIMIT
		  and ((o.side = com.finplay.api.order.domain.OrderSide.BUY and o.limitPrice >= :price)
		    or (o.side = com.finplay.api.order.domain.OrderSide.SELL and o.limitPrice <= :price))
		order by o.requestedAt asc, o.id asc
		""")
	List<Order> findPendingLimitOrdersToFill(@Param("instrumentId")
	Long instrumentId, @Param("price")
	BigDecimal price);
}
