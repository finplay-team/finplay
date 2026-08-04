// 주문 요청 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {

	@Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.user.id = :userId ORDER BY o.requestedAt DESC, o.id DESC")
	List<Order> findAllByUserIdOrderByRequestedAtDescIdDesc(@Param("userId")
	Long userId);

	@Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.user.id = :userId AND o.idempotencyKey = :idempotencyKey")
	Optional<Order> findByUserIdAndIdempotencyKey(
		@Param("userId")
		Long userId, @Param("idempotencyKey")
		String idempotencyKey);
}
