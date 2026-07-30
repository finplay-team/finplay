// 수집 시도 이력의 영속화와 거래일 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.MarketDataImport;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDataImportRepository extends JpaRepository<MarketDataImport, Long> {

	List<MarketDataImport> findBySourceTradingDateOrderByCollectedAtDesc(LocalDate sourceTradingDate);
}
