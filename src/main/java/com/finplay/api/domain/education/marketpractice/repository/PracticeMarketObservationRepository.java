package com.finplay.api.domain.education.marketpractice.repository;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeMarketObservationRepository extends JpaRepository<PracticeMarketObservation, Long> {

	List<PracticeMarketObservation> findByUserIdAndHoldingIdOrderByObservedAtAsc(Long userId, Long holdingId);

	List<PracticeMarketObservation> findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(Long userId, Long holdingId);
}
