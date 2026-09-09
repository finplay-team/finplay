package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketNewsItemRepository extends JpaRepository<MarketNewsItem, Long> {

	@Query("SELECT n.url FROM MarketNewsItem n WHERE n.instrument.id = :instrumentId AND n.url IN :urls")
	List<String> findExistingUrls(@Param("instrumentId")
	Long instrumentId, @Param("urls")
	Collection<String> urls);

	List<MarketNewsItem> findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
		Long instrumentId, MarketNewsItemType type, LocalDateTime from, LocalDateTime to);

	@Query("SELECT n FROM MarketNewsItem n WHERE n.instrument.id = :instrumentId "
		+ "AND n.type = com.finplay.api.domain.feedback.entity.MarketNewsItemType.DISCLOSURE "
		+ "AND n.publishedAt >= :fromInclusive AND n.publishedAt < :toExclusive "
		+ "ORDER BY n.publishedAt ASC")
	List<MarketNewsItem> findDisclosuresReceivedOn(@Param("instrumentId")
	Long instrumentId, @Param("fromInclusive")
	LocalDateTime fromInclusive, @Param("toExclusive")
	LocalDateTime toExclusive);

	@Query("SELECT n FROM MarketNewsItem n JOIN FETCH n.instrument i "
		+ "WHERE i.market = :market AND i.tutorialSample = false "
		+ "AND n.type = com.finplay.api.domain.feedback.entity.MarketNewsItemType.NEWS "
		+ "AND n.publishedAt >= :fromInclusive AND n.publishedAt <= :toInclusive")
	List<MarketNewsItem> findMarketNewsPublishedBetween(@Param("market")
	Market market, @Param("fromInclusive")
	LocalDateTime fromInclusive, @Param("toInclusive")
	LocalDateTime toInclusive);

	@Query("SELECT n FROM MarketNewsItem n JOIN FETCH n.instrument i "
		+ "WHERE i.market = :market AND i.tutorialSample = false "
		+ "AND n.type = com.finplay.api.domain.feedback.entity.MarketNewsItemType.DISCLOSURE "
		+ "AND n.publishedAt >= :fromInclusive AND n.publishedAt < :toExclusive")
	List<MarketNewsItem> findMarketDisclosuresReceivedOn(@Param("market")
	Market market, @Param("fromInclusive")
	LocalDateTime fromInclusive, @Param("toExclusive")
	LocalDateTime toExclusive);

	boolean existsByInstrumentIdAndCreatedAtAfter(Long instrumentId, LocalDateTime since);

	@Query("SELECT COUNT(n) > 0 FROM MarketNewsItem n "
		+ "WHERE n.instrument.market = :market AND n.instrument.tutorialSample = false "
		+ "AND n.createdAt > :since")
	boolean existsCollectedAfter(@Param("market")
	Market market, @Param("since")
	LocalDateTime since);
}
