// 관심목록 항목의 저장·조회를 담당하는 리포지토리
package com.finplay.api.watchlist.repository;

import com.finplay.api.market.domain.Market;
import com.finplay.api.watchlist.domain.WatchlistItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

	List<WatchlistItem> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	List<WatchlistItem> findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(Long userId, Market market);

	Optional<WatchlistItem> findByUserIdAndInstrumentId(Long userId, Long instrumentId);

	boolean existsByUserIdAndInstrumentId(Long userId, Long instrumentId);
}
