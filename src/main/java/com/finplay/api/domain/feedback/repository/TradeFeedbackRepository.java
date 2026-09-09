package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.TradeFeedback;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeFeedbackRepository extends JpaRepository<TradeFeedback, Long> {

	Optional<TradeFeedback> findByTradeId(Long tradeId);
}
