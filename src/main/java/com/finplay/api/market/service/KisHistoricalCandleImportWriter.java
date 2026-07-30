// KisHistoricalCandleCollector의 수집 결과 저장과 실패 이력 기록만을 담당하는 트랜잭션 경계 전용 컴포넌트
package com.finplay.api.market.service;

import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.MarketDataImport;
import com.finplay.api.market.repository.MarketDataImportRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// KisHistoricalCandleCollector.collect()는 종목별 KIS HTTP 호출(collectInstrument)을 모두 끝낸 뒤에만 이 컴포넌트를
// 호출한다 — DB 저장(persist)과 실패 이력 기록(recordFailedImport)만 트랜잭션으로 감싸 외부 HTTP 호출 동안 DB 커넥션을
// 점유하지 않는다(PR #94 리뷰 권장사항 ②).
@Component
@RequiredArgsConstructor
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
			// 대상 종목 전부가 실패(구조 오류든 종목별 조회 실패든)라면 "부분" 성공이 아니라 사실상 전체 실패다 —
			// StockCandle은 어차피 하나도 저장되지 않으므로(succeededOutcomes가 비어 있음) PARTIAL_SUCCESS로 표시하지
			// 않는다.
			status = ImportStatus.FAILED;
			failureReason = summarizeFailures(failedOutcomes);
		} else {
			status = ImportStatus.PARTIAL_SUCCESS;
			failureReason = summarizeFailures(failedOutcomes);
		}
		marketDataImportRepository.save(
			MarketDataImport.create(DATA_SOURCE, tradingDate, collectedAt, status, failureReason));
	}

	// FAILED 이력 저장 전용 — 호출부(collect())의 예외가 JPA 트랜잭션 도중 발생했다면 그 세션이 rollback-only가 되어
	// 같은 트랜잭션에서 이력 저장을 시도하면 실패할 수 있다(PR #94 리뷰 권장사항 ④). 항상 새 트랜잭션에서 저장한다.
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
