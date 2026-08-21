// 관심목록 항목의 저장·조회를 담당하는 리포지토리
package com.finplay.api.domain.watchlist.repository;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

	// 응답 매핑이 instrument의 필드 4개를 전부 읽으므로 JOIN FETCH로 N+1을 막는다 (HoldingRepository 선례).
	@Query("SELECT w FROM WatchlistItem w JOIN FETCH w.instrument WHERE w.userId = :userId "
		+ "ORDER BY w.createdAt DESC, w.id DESC")
	List<WatchlistItem> findByUserIdOrderByCreatedAtDescIdDesc(@Param("userId")
	Long userId);

	@Query("SELECT w FROM WatchlistItem w JOIN FETCH w.instrument WHERE w.userId = :userId "
		+ "AND w.instrument.market = :market ORDER BY w.createdAt DESC, w.id DESC")
	List<WatchlistItem> findByUserIdAndInstrument_MarketOrderByCreatedAtDescIdDesc(@Param("userId")
	Long userId, @Param("market")
	Market market);

	Optional<WatchlistItem> findByUserIdAndInstrumentId(Long userId, Long instrumentId);

	boolean existsByUserIdAndInstrumentId(Long userId, Long instrumentId);
}
