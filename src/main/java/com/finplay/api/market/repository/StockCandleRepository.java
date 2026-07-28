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
}
