// 실수집 트리거의 DB 작업만 별도 트랜잭션으로 처리하는 로컬 개발용 컴포넌트 (local 프로필 전용)
package com.finplay.api.market.service;

import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// StockReplayImportTriggerService는 KIS 수집 배치(16종 순차 HTTP)를 트랜잭션 밖에서 호출해야 하므로 DB 작업을 스스로
// @Transactional로 감쌀 수 없다. 같은 빈 안의 자기 호출은 프록시를 거치지 않아 @Transactional이 적용되지 않기 때문에,
// KisHistoricalCandleCollector가 KisHistoricalCandleImportWriter에 DB 작업을 위임한 것과 같은 방식으로 분리한다.
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
