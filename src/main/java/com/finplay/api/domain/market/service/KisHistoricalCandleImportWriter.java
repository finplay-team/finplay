package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
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
class KisHistoricalCandleImportWriter {

	private static final String DATA_SOURCE = "KIS";
	private static final int MAX_FAILURE_REASON_LENGTH = 500;

	private final StockCandleRepository stockCandleRepository;
	private final MarketDataImportRepository marketDataImportRepository;

	@Transactional
	void persist(LocalDate tradingDate, LocalDateTime collectedAt, List<InstrumentOutcome> outcomes) {
		List<InstrumentOutcome> failedOutcomes = outcomes.stream().filter(outcome -> outcome.failureReason() != null)
			.toList();
		List<InstrumentOutcome> succeededOutcomes = outcomes.stream().filter(outcome -> outcome.failureReason() == null)
			.toList();

		for (InstrumentOutcome outcome : succeededOutcomes) {
			if (!outcome.candles().isEmpty()) {
				stockCandleRepository.saveAll(outcome.candles());
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
			MarketDataImport.create(DATA_SOURCE, tradingDate, collectedAt, status, failureReason));

		if (status == ImportStatus.FAILED) {
			log.error("주식 분봉 수집이 {}로 끝났습니다 (tradingDate={}, failureReason={})", status, tradingDate,
				failureReason);
		} else if (status == ImportStatus.PARTIAL_SUCCESS) {
			log.warn("주식 분봉 수집이 {}로 끝났습니다 (tradingDate={}, failureReason={})", status, tradingDate,
				failureReason);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void recordFailedImport(LocalDate tradingDate, LocalDateTime collectedAt, String failureReason) {
		marketDataImportRepository.save(MarketDataImport.create(
			DATA_SOURCE, tradingDate, collectedAt, ImportStatus.FAILED, truncateReason(failureReason)));
	}

	private static String summarizeFailures(List<InstrumentOutcome> failedOutcomes) {
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
