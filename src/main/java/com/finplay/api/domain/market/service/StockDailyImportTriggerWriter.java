// 일봉 아카이브 실수집 트리거의 DB 조회만 별도 트랜잭션으로 처리하는 로컬 개발용 컴포넌트 (local 프로필 전용)
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

// StockDailyImportTriggerService는 KIS 수집 배치(종목별 순차 HTTP)를 트랜잭션 밖에서 호출해야 하므로 DB 조회를
// 스스로 @Transactional로 감쌀 수 없다 — StockReplayImportTriggerWriter와 같은 이유로 분리한다.
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
