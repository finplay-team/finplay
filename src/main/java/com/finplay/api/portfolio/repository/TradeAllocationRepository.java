// 매도 체결의 lot별 배분 내역 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.TradeAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeAllocationRepository extends JpaRepository<TradeAllocation, Long> {}
