// 주문 요청 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {}
