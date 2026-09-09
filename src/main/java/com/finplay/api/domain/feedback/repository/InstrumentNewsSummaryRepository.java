package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentNewsSummaryRepository extends JpaRepository<InstrumentNewsSummary, Long> {

	boolean existsByInstrumentIdAndOriginTradeDateAndScope(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope);

	Optional<InstrumentNewsSummary> findByInstrumentIdAndOriginTradeDateAndScope(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope);

	Optional<InstrumentNewsSummary> findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
		Long instrumentId, NewsSummaryScope scope);
}
