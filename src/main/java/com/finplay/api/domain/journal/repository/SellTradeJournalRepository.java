package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.SellTradeJournal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellTradeJournalRepository
	extends JpaRepository<SellTradeJournal, Long>, SellTradeJournalRepositoryCustom {

	boolean existsBySellTradeId(Long sellTradeId);

	Optional<SellTradeJournal> findBySellTradeId(Long sellTradeId);
}
