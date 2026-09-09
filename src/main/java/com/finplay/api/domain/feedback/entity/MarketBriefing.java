package com.finplay.api.domain.feedback.entity;

import com.finplay.api.domain.market.entity.Market;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "market_briefings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketBriefing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private MarketBriefing(
		Market market,
		LocalDate originTradeDate,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		this.market = market;
		this.originTradeDate = originTradeDate;
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}

	public static MarketBriefing create(
		Market market,
		LocalDate originTradeDate,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		return new MarketBriefing(market, originTradeDate, summary, narrativeSource, generatedAt);
	}

	public void refreshNarrative(String summary, NarrativeSource narrativeSource, LocalDateTime generatedAt) {
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}
}
