package com.finplay.api.domain.feedback.entity;

import com.finplay.api.domain.order.entity.Trade;
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
@Table(name = "trade_feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeFeedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "trade_id", nullable = false)
	private Trade trade;

	@Column(columnDefinition = "TEXT")
	private String narrative;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "narrative_finalized", nullable = false)
	private boolean narrativeFinalized;

	@Column(name = "regeneration_attempts", nullable = false)
	private int regenerationAttempts;

	@Column(name = "journal_fingerprint", length = 64)
	private String journalFingerprint;

	@Column(name = "journal_regenerations", nullable = false)
	private int journalRegenerations;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private TradeFeedback(
		Trade trade,
		String narrative,
		NarrativeSource narrativeSource,
		boolean narrativeFinalized,
		int regenerationAttempts,
		String journalFingerprint,
		int journalRegenerations,
		LocalDateTime generatedAt) {
		this.trade = trade;
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.narrativeFinalized = narrativeFinalized;
		this.regenerationAttempts = regenerationAttempts;
		this.journalFingerprint = journalFingerprint;
		this.journalRegenerations = journalRegenerations;
		this.generatedAt = generatedAt;
	}

	public static TradeFeedback create(
		Trade trade,
		String narrative,
		NarrativeSource narrativeSource,
		String journalFingerprint,
		LocalDateTime generatedAt) {
		return new TradeFeedback(trade, narrative, narrativeSource, false, 0, journalFingerprint, 0, generatedAt);
	}

	public void applyRegeneratedNarrative(
		String narrative, NarrativeSource narrativeSource, String journalFingerprint, LocalDateTime generatedAt) {
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.narrativeFinalized = true;
		this.regenerationAttempts++;
		this.journalFingerprint = journalFingerprint;
		this.generatedAt = generatedAt;
	}

	public void applyJournalRegeneratedNarrative(
		String narrative, NarrativeSource narrativeSource, String journalFingerprint, LocalDateTime generatedAt) {
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.journalRegenerations++;
		this.journalFingerprint = journalFingerprint;
		this.generatedAt = generatedAt;
	}

	public void countJournalRegeneration() {
		this.journalRegenerations++;
	}

	public void recordFailedRegeneration() {
		this.regenerationAttempts++;
	}
}
