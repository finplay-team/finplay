package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceMoveEventRepository extends JpaRepository<PriceMoveEvent, Long> {

	boolean existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
		Long instrumentId, LocalDate originTradeDate, PriceMoveEventType eventType, LocalTime windowStart);

	List<PriceMoveEvent> findByInstrumentIdAndOriginTradeDateAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
		Long instrumentId, LocalDate originTradeDate, LocalTime revealTime);

	List<PriceMoveEvent> findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
		Long instrumentId,
		LocalDate originTradeDate,
		LocalTime windowEndFrom,
		LocalTime windowEndTo,
		LocalTime revealTime);

	List<PriceMoveEvent> findByMarketAndOriginTradeDate(Market market, LocalDate originTradeDate);

	List<PriceMoveEvent> findByMarketAndOccurredAtBetween(
		Market market, LocalDateTime occurredAtFrom, LocalDateTime occurredAtTo);

	Optional<PriceMoveEvent> findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(
		Long instrumentId, Market market);

	long countByInstrumentIdAndMarketAndOriginTradeDate(
		Long instrumentId, Market market, LocalDate originTradeDate);

	List<PriceMoveEvent> findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
		Long instrumentId, Market market, LocalDateTime occurredAtFrom, LocalDateTime occurredAtTo);
}
