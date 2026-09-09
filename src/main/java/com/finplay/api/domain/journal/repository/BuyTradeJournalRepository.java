package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuyTradeJournalRepository
	extends JpaRepository<BuyTradeJournal, Long>, BuyTradeJournalRepositoryCustom {

	boolean existsByBuyTradeId(Long buyTradeId);

	Optional<BuyTradeJournal> findByBuyTradeId(Long buyTradeId);

	@Query("select j from BuyTradeJournal j "
		+ "join fetch j.buyTrade buyTrade "
		+ "where buyTrade.id in :buyTradeIds")
	List<BuyTradeJournal> findAllByBuyTradeIdIn(@Param("buyTradeIds")
	Collection<Long> buyTradeIds);
}
