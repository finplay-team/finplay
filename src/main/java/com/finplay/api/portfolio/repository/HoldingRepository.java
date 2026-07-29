// 계좌·종목별 보유 현황 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingRepository extends JpaRepository<Holding, Long> {}
