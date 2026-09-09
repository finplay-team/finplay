package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.StockDailyCandle;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockDailyCandleRepository extends JpaRepository<StockDailyCandle, Long> {

	Optional<StockDailyCandle> findFirstByInstrumentIdOrderByTradingDateDesc(Long instrumentId);

	List<StockDailyCandle> findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(
		Long instrumentId, LocalDate from, LocalDate to);

	long countByDataSource(String dataSource);
}
