// 매도 체결별 매도 회고 영속을 담당하는 JPA 리포지터리
package com.finplay.api.journal.repository;

import com.finplay.api.journal.domain.SellTradeJournal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellTradeJournalRepository
	extends JpaRepository<SellTradeJournal, Long>, SellTradeJournalRepositoryCustom {

	boolean existsBySellTradeId(Long sellTradeId);

	Optional<SellTradeJournal> findBySellTradeId(Long sellTradeId);
}
