package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@RequiredArgsConstructor
public class StockReplayImportTriggerWriter {

	private final StockCandleRepository stockCandleRepository;
	private final StockReplaySessionRepository stockReplaySessionRepository;

	@Transactional(readOnly = true)
	public long countCandles(LocalDate tradingDate, String dataSource) {
		return stockCandleRepository.countByTradingDateAndDataSource(tradingDate, dataSource);
	}

	@Transactional(readOnly = true)
	public Optional<StockReplaySession> findSession(LocalDate serviceDate) {
		return stockReplaySessionRepository.findByServiceDate(serviceDate);
	}
}
