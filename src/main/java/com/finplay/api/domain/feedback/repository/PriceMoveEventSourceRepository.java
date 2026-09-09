package com.finplay.api.domain.feedback.repository;

import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceMoveEventSourceRepository extends JpaRepository<PriceMoveEventSource, Long> {

	@Query("SELECT s FROM PriceMoveEventSource s JOIN FETCH s.marketNewsItem n "
		+ "WHERE s.priceMoveEvent.id IN :priceMoveEventIds "
		+ "ORDER BY n.publishedAt DESC, s.id ASC")
	List<PriceMoveEventSource> findAllByPriceMoveEventIdIn(@Param("priceMoveEventIds")
	Collection<Long> priceMoveEventIds);
}
