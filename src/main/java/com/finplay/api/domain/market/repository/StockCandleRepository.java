package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.StockCandle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCandleRepository extends JpaRepository<StockCandle, Long> {

	List<StockCandle> findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findByInstrumentIdAndTradingDateAndCandleTime(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(
		Long instrumentId, LocalDate tradingDate);

	List<StockCandle> findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate, LocalTime from, LocalTime to);

	List<StockCandle> findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
		Long instrumentId, LocalDate from, LocalDate to);

	boolean existsByTradingDate(LocalDate tradingDate);

	boolean existsByInstrumentIdAndTradingDate(Long instrumentId, LocalDate tradingDate);

	long countByTradingDateAndDataSource(LocalDate tradingDate, String dataSource);

	@Query("SELECT DISTINCT c.tradingDate FROM StockCandle c "
		+ "WHERE c.instrument.id = :instrumentId AND c.tradingDate BETWEEN :from AND :to "
		+ "ORDER BY c.tradingDate DESC")
	List<LocalDate> findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
		@Param("instrumentId")
		Long instrumentId, @Param("from")
		LocalDate from, @Param("to")
		LocalDate to,
		Pageable pageable);
}
