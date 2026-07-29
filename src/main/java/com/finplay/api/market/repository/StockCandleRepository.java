// 주식 1분봉의 영속화와 거래일·시각 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.StockCandle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCandleRepository extends JpaRepository<StockCandle, Long> {

	List<StockCandle> findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findByInstrumentIdAndTradingDateAndCandleTime(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	// 캔들 API — 종목·거래일·분봉시각(from~to, 양끝 포함) 범위 조회
	List<StockCandle> findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate, LocalTime from, LocalTime to);

	// StockReplaySessionScheduler 전용 — 후보 거래일에 실제로 저장된 분봉이 있는지 확인(종목 무관)
	boolean existsByTradingDate(LocalDate tradingDate);

	// KisHistoricalCandleCollector 전용 — 종목·거래일 단위 멱등 스킵 판정. 이미 수집된 날 최대 390행을 전부 로드하는
	// findByInstrumentIdAndTradingDateOrderByCandleTimeAsc 대신 존재 여부만 확인한다(PR #94 리뷰 권장사항).
	boolean existsByInstrumentIdAndTradingDate(Long instrumentId, LocalDate tradingDate);
}
