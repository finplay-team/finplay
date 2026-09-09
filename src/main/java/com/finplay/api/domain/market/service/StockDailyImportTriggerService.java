package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.dto.response.StockDailyImportTriggerResponse;
import com.finplay.api.domain.market.entity.MarketDataImport;
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
public class StockDailyImportTriggerService {

	private final StockDailyCandleCollector stockDailyCandleCollector;
	private final StockDailyImportTriggerWriter writer;
	private final BusinessDayCalendar businessDayCalendar;
	private final Clock clock;

	public StockDailyImportTriggerResponse trigger() {
		LocalDate serviceDate = LocalDate.now(clock);
		LocalDate targetEndDate = businessDayCalendar.previousBusinessDay(serviceDate);

		long countBefore = writer.countArchivedCandles();
		stockDailyCandleCollector.collect();
		long countAfter = writer.countArchivedCandles();
		long newlyCollected = countAfter - countBefore;

		MarketDataImport latestImport = writer.findLatestImport(targetEndDate).orElse(null);
		log.info("KIS 일봉 아카이브 실수집 트리거 완료 (targetEndDate={}, 신규 {}건, 누적 {}건, status={})", targetEndDate,
			newlyCollected, countAfter, latestImport == null ? "이력없음" : latestImport.getStatus());

		return new StockDailyImportTriggerResponse(
			serviceDate,
			targetEndDate,
			newlyCollected,
			countAfter,
			latestImport == null ? null : latestImport.getStatus().name(),
			latestImport == null ? null : latestImport.getFailureReason());
	}
}
