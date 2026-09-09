package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@RequiredArgsConstructor
public class StockDailyImportTriggerWriter {

	private final StockDailyCandleRepository stockDailyCandleRepository;
	private final MarketDataImportRepository marketDataImportRepository;

	@Transactional(readOnly = true)
	public long countArchivedCandles() {
		return stockDailyCandleRepository.countByDataSource(StockDailyCandleImportWriter.DATA_SOURCE);
	}

	@Transactional(readOnly = true)
	public Optional<MarketDataImport> findLatestImport(LocalDate targetEndDate) {
		return marketDataImportRepository.findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
			StockDailyCandleImportWriter.DATA_SOURCE, targetEndDate);
	}
}
