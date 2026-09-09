package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReplaySessionRepository extends JpaRepository<StockReplaySession, Long> {

	Optional<StockReplaySession> findByServiceDate(LocalDate serviceDate);

	Optional<StockReplaySession> findFirstByOrderByServiceDateDesc();

	Optional<StockReplaySession> findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
		LocalDate serviceDate, PreparationStatus preparationStatus);
}
