package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.MarketDataImport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDataImportRepository extends JpaRepository<MarketDataImport, Long> {

	List<MarketDataImport> findBySourceTradingDateOrderByCollectedAtDesc(LocalDate sourceTradingDate);

	Optional<MarketDataImport> findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
		String source, LocalDate sourceTradingDate);
}
