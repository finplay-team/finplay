// 체결 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.order.domain.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, Long> {}
