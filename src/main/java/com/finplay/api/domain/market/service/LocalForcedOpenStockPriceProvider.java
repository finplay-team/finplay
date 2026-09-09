package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Primary
@Profile("local")
public class LocalForcedOpenStockPriceProvider implements StockPriceProvider {

	private final KisHistoricalReplayPriceProvider delegate;
	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final Clock clock;
	private final boolean forceMarketOpen;

	public LocalForcedOpenStockPriceProvider(
		KisHistoricalReplayPriceProvider delegate,
		StockReplaySessionRepository stockReplaySessionRepository,
		Clock clock,
		@Value("${finplay.dev.stock.force-market-open:false}")
		boolean forceMarketOpen) {
		this.delegate = delegate;
		this.stockReplaySessionRepository = stockReplaySessionRepository;
		this.clock = clock;
		this.forceMarketOpen = forceMarketOpen;
	}

	@Override
	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		StockMarketStatus actual = delegate.getMarketStatus();
		if (!forceMarketOpen || actual == StockMarketStatus.OPEN) {
			return actual;
		}
		boolean sessionReady = stockReplaySessionRepository
			.findByServiceDate(LocalDate.now(clock))
			.filter(session -> session.getPreparationStatus() == PreparationStatus.READY)
			.isPresent();
		return sessionReady ? StockMarketStatus.OPEN : actual;
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return forceOpenWhenReady(delegate.getCurrentPrice(instrumentId));
	}

	private StockReplayPriceDto forceOpenWhenReady(StockReplayPriceDto quote) {
		if (!forceMarketOpen || quote.marketStatus() == StockMarketStatus.OPEN || !quote.sessionReady()) {
			return quote;
		}
		return new StockReplayPriceDto(
			quote.sessionReady(), StockMarketStatus.OPEN, quote.sourceTradingDate(), quote.price(), quote.sourceTime(),
			quote.replaySession());
	}

	@Override
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		return delegate.getCurrentPrices(instrumentIds).stream().map(this::forceOpenWhenReady).toList();
	}

	@Override
	public List<StockCandleDto> getCandles(
		Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		return delegate.getCandles(instrumentId, interval, from, to);
	}
}
