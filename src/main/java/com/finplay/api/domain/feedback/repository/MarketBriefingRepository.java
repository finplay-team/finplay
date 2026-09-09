package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketBriefingRepository extends JpaRepository<MarketBriefing, Long> {

	boolean existsByMarketAndOriginTradeDate(Market market, LocalDate originTradeDate);

	Optional<MarketBriefing> findByMarketAndOriginTradeDate(Market market, LocalDate originTradeDate);

	Optional<MarketBriefing> findFirstByMarketOrderByGeneratedAtDescIdDesc(Market market);
}
