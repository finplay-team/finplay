package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class PriceMoveCardWriter {

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Transactional
	PriceMoveEvent persist(PriceMoveEvent card, List<MarketNewsItem> sources) {
		PriceMoveEvent saved = priceMoveEventRepository.save(card);
		priceMoveEventSourceRepository.saveAll(
			sources.stream().map(source -> PriceMoveEventSource.of(saved, source)).toList());
		return saved;
	}
}
