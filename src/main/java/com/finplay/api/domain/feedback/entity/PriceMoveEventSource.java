package com.finplay.api.domain.feedback.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "price_move_event_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMoveEventSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "price_move_event_id", nullable = false)
	private PriceMoveEvent priceMoveEvent;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "market_news_item_id", nullable = false)
	private MarketNewsItem marketNewsItem;

	private PriceMoveEventSource(PriceMoveEvent priceMoveEvent, MarketNewsItem marketNewsItem) {
		this.priceMoveEvent = priceMoveEvent;
		this.marketNewsItem = marketNewsItem;
	}

	public static PriceMoveEventSource of(PriceMoveEvent priceMoveEvent, MarketNewsItem marketNewsItem) {
		return new PriceMoveEventSource(priceMoveEvent, marketNewsItem);
	}
}
