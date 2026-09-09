package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceMovePeerStatRepository extends JpaRepository<PriceMovePeerStat, Long> {

	boolean existsByPriceMoveEventIdAndServiceDate(Long priceMoveEventId, LocalDate serviceDate);

	Optional<PriceMovePeerStat> findByPriceMoveEventIdAndServiceDate(Long priceMoveEventId, LocalDate serviceDate);
}
