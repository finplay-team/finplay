package com.finplay.api.domain.watchlist.entity;

import com.finplay.api.domain.market.entity.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "watchlist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchlistItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private WatchlistItem(Long userId, Instrument instrument, LocalDateTime now) {
		this.userId = userId;
		this.instrument = instrument;
		this.createdAt = now;
	}

	public static WatchlistItem create(Long userId, Instrument instrument, LocalDateTime now) {
		return new WatchlistItem(userId, instrument, now);
	}
}
