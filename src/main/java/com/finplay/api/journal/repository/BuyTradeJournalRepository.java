// 매수 체결별 투자일기 영속을 담당하는 JPA 리포지터리
package com.finplay.api.journal.repository;

import com.finplay.api.journal.domain.BuyTradeJournal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyTradeJournalRepository extends JpaRepository<BuyTradeJournal, Long> {

	boolean existsByBuyTradeId(Long buyTradeId);

	Optional<BuyTradeJournal> findByBuyTradeId(Long buyTradeId);
}
