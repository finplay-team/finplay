package com.finplay.api.domain.watchlist.repository;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

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
