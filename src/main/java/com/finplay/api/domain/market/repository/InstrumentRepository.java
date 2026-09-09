package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

	List<Instrument> findAllByOrderByIdAsc();

	List<Instrument> findByMarketOrderByIdAsc(Market market);

	List<Instrument> findByMarketAndTradableTrueOrderByIdAsc(Market market);

	List<Instrument> findByMarketAndTutorialSampleFalseOrderByIdAsc(Market market);

	List<Instrument> findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market market);

	Optional<Instrument> findByMarketAndSymbol(Market market, String symbol);
}
