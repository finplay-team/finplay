package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.dto.response.StockReplayImportTriggerResponse;
import com.finplay.api.domain.market.entity.StockReplaySession;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class StockReplayImportTriggerService {

	private static final String KIS_DATA_SOURCE = "KIS";

	private final KisHistoricalCandleCollector kisHistoricalCandleCollector;
	private final StockReplaySessionScheduler stockReplaySessionScheduler;
	private final StockReplayImportTriggerWriter writer;
	private final StockPriceProvider stockPriceProvider;
	private final BusinessDayCalendar businessDayCalendar;
	private final Clock clock;

	public StockReplayImportTriggerResponse trigger() {
		LocalDate serviceDate = LocalDate.now(clock);
		LocalDate tradingDate = businessDayCalendar.previousBusinessDay(serviceDate);

		kisHistoricalCandleCollector.collect();
		stockReplaySessionScheduler.resolveTodaySession();

		long collected = writer.countCandles(tradingDate, KIS_DATA_SOURCE);
		StockReplaySession session = writer.findSession(serviceDate).orElse(null);
		String marketStatus = stockPriceProvider.getMarketStatus().name();
		log.info("KIS 실수집 트리거 완료 (tradingDate={}, KIS 분봉 {}건, 세션={}, marketStatus={})", tradingDate, collected,
			session == null ? "없음" : session.getPreparationStatus(), marketStatus);

		return new StockReplayImportTriggerResponse(
			serviceDate,
			tradingDate,
			collected,
			session == null ? null : session.getPreparationStatus().name(),
			session == null ? null : session.getFailureReason(),
			marketStatus);
	}
}
