package com.finplay.api.domain.feedback.entity;

import com.finplay.api.domain.market.entity.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "market_news_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketNewsItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MarketNewsItemType type;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(nullable = false, length = 100)
	private String publisher;

	@Column(nullable = false, length = 500)
	private String url;

	@Column(name = "published_at", nullable = false)
	private LocalDateTime publishedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private MarketNewsItem(
		Instrument instrument,
		MarketNewsItemType type,
		String title,
		String publisher,
		String url,
		LocalDateTime publishedAt,
		LocalDateTime createdAt) {
		this.instrument = instrument;
		this.type = type;
		this.title = title;
		this.publisher = publisher;
		this.url = url;
		this.publishedAt = publishedAt;
		this.createdAt = createdAt;
	}

	public static MarketNewsItem create(
		Instrument instrument,
		MarketNewsItemType type,
		String title,
		String publisher,
		String url,
		LocalDateTime publishedAt,
		LocalDateTime now) {
		return new MarketNewsItem(instrument, type, title, publisher, url, publishedAt, now);
	}
}
