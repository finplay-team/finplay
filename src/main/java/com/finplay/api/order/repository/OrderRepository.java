// 주문 요청 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
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
	// practicePriceSessionId is null 조건으로 교육 세션 주문을 제외한다(030 역방향 오염 차단 — 실제 빗썸 시세 tick이
	// 교육 주문을 체결하지 않는다). 교육 주문은 전용 이벤트(PracticeOrderSettlementService)로만 체결한다.
	@Query("""
		select o from Order o
		where o.instrument.id = :instrumentId and o.status = com.finplay.api.order.domain.OrderStatus.PENDING
		  and o.orderType = com.finplay.api.order.domain.OrderType.LIMIT
		  and o.practicePriceSessionId is null
		  and ((o.side = com.finplay.api.order.domain.OrderSide.BUY and o.limitPrice >= :price)
		    or (o.side = com.finplay.api.order.domain.OrderSide.SELL and o.limitPrice <= :price))
		order by o.requestedAt asc, o.id asc
		""")
	List<Order> findPendingLimitOrdersToFill(@Param("instrumentId")
	Long instrumentId, @Param("price")
	BigDecimal price);

	// 교육 지정가 생성 시 세션당 PENDING 1건 상한 검증용(030 COIN-PRICE-RUNTIME-006). 호출부가 세션을
	// 먼저 비관 잠금해 동시 생성을 직렬화하므로 이 조회 자체는 락을 걸지 않는다(plan.md "트랜잭션·잠금·이벤트").
	boolean existsByPracticePriceSessionIdAndStatus(Long practicePriceSessionId, OrderStatus status);

	// tick 체결·마지막 tick 취소 대상 세션 PENDING 주문을 id 오름차순 단일 FOR UPDATE로 일괄 잠근다
	// (plan.md 잠금 순서 practice_price_session → order(id ASC) → account → holding).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select o from Order o
		where o.practicePriceSessionId = :sessionId and o.status = com.finplay.api.order.domain.OrderStatus.PENDING
		order by o.id asc
		""")
	List<Order> findPendingBySessionIdForUpdate(@Param("sessionId")
	Long sessionId);
}
