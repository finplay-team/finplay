// 종목의 영속화와 시장별 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

	List<Instrument> findAllByOrderByIdAsc();

	List<Instrument> findByMarketOrderByIdAsc(Market market);
}
