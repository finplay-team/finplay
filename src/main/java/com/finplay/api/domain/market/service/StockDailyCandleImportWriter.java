package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
class StockDailyCandleImportWriter {

	static final String DATA_SOURCE = "KIS_DAILY";
	private static final int MAX_FAILURE_REASON_LENGTH = 500;

	private final StockDailyCandleRepository stockDailyCandleRepository;
	private final MarketDataImportRepository marketDataImportRepository;

	@Transactional
	void persist(LocalDate anchorDate, LocalDateTime collectedAt, List<DailyInstrumentOutcome> outcomes) {
		List<DailyInstrumentOutcome> failedOutcomes = outcomes.stream()
			.filter(outcome -> outcome.failureReason() != null)
			.toList();
		List<DailyInstrumentOutcome> succeededOutcomes = outcomes.stream()
			.filter(outcome -> outcome.failureReason() == null)
			.toList();

		for (DailyInstrumentOutcome outcome : succeededOutcomes) {
			if (!outcome.candles().isEmpty()) {
				stockDailyCandleRepository.saveAll(outcome.candles());
			}
		}

		ImportStatus status;
		String failureReason = null;
		if (failedOutcomes.isEmpty()) {
			status = ImportStatus.SUCCESS;
		} else if (succeededOutcomes.isEmpty()) {
			status = ImportStatus.FAILED;
			failureReason = summarizeFailures(failedOutcomes);
		} else {
			status = ImportStatus.PARTIAL_SUCCESS;
			failureReason = summarizeFailures(failedOutcomes);
		}
		marketDataImportRepository.save(
			MarketDataImport.create(DATA_SOURCE, anchorDate, collectedAt, status, failureReason));

		if (status == ImportStatus.FAILED) {
			log.error("주식 일봉 아카이브 수집이 {}로 끝났습니다 (anchorDate={}, failureReason={})", status, anchorDate,
				failureReason);
		} else if (status == ImportStatus.PARTIAL_SUCCESS) {
			log.warn("주식 일봉 아카이브 수집이 {}로 끝났습니다 (anchorDate={}, failureReason={})", status, anchorDate,
				failureReason);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void recordFailedImport(LocalDate anchorDate, LocalDateTime collectedAt, String failureReason) {
		marketDataImportRepository.save(MarketDataImport.create(
			DATA_SOURCE, anchorDate, collectedAt, ImportStatus.FAILED, truncateReason(failureReason)));
	}

	private static String summarizeFailures(List<DailyInstrumentOutcome> failedOutcomes) {
		String joined = failedOutcomes.stream()
			.map(outcome -> outcome.instrument().getSymbol() + ": " + outcome.failureReason())
			.collect(Collectors.joining("; "));
		return truncateReason(joined);
	}

	private static String truncateReason(String reason) {
		if (reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH) {
			return reason;
		}
		return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
	}
}
