// StockDailyCandleCollector의 수집 결과 저장과 실패 이력 기록만을 담당하는 트랜잭션 경계 전용 컴포넌트
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

// StockDailyCandleCollector.collect()는 종목별 KIS HTTP 호출(collectInstrument)을 모두 끝낸 뒤에만 이 컴포넌트를
// 호출한다 — DB 저장(persist)과 실패 이력 기록(recordFailedImport)만 트랜잭션으로 감싸 외부 HTTP 호출 동안 DB 커넥션을
// 점유하지 않는다(KisHistoricalCandleImportWriter와 같은 패턴, PR #94 리뷰 권장사항 ②).
@Component
@RequiredArgsConstructor
@Slf4j
class StockDailyCandleImportWriter {

	// StockDailyCandle.dataSource와 MarketDataImport.source 양쪽에 쓰는 값 — 1분봉 수집("KIS")과 구분해
	// market_data_imports 이력에서 어느 배치인지 바로 알 수 있게 한다.
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
			// 대상 종목 전부가 실패했다면 "부분" 성공이 아니라 사실상 전체 실패다 — StockDailyCandle은 어차피
			// 하나도 저장되지 않으므로(succeededOutcomes가 비어 있음) PARTIAL_SUCCESS로 표시하지 않는다.
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

	// FAILED 이력 저장 전용 — 호출부(collect())의 예외가 JPA 트랜잭션 도중 발생했다면 그 세션이 rollback-only가 되어
	// 같은 트랜잭션에서 이력 저장을 시도하면 실패할 수 있다. 항상 새 트랜잭션에서 저장한다(KisHistoricalCandleImportWriter와 동일).
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
