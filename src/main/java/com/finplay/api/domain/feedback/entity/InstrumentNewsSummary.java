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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "instrument_news_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstrumentNewsSummary {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NewsSummaryScope scope;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private InstrumentNewsSummary(
		Instrument instrument,
		LocalDate originTradeDate,
		NewsSummaryScope scope,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		this.instrument = instrument;
		this.originTradeDate = originTradeDate;
		this.scope = scope;
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}

	public static InstrumentNewsSummary create(
		Instrument instrument,
		LocalDate originTradeDate,
		NewsSummaryScope scope,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		return new InstrumentNewsSummary(instrument, originTradeDate, scope, summary, narrativeSource, generatedAt);
	}

	public void refreshNarrative(String summary, NarrativeSource narrativeSource, LocalDateTime generatedAt) {
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}
}
