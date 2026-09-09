package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.NarrativeSource;

public record NarrativeResultDto(String narrative, NarrativeSource source) {

	public NarrativeResultDto {
		if (source == null) {
			throw new IllegalArgumentException("narrative_source는 null일 수 없습니다.");
		}
		if (source == NarrativeSource.NONE && narrative != null) {
			throw new IllegalArgumentException("NONE은 서술이 없는 상태입니다. narrative는 null이어야 합니다.");
		}
		if (source != NarrativeSource.NONE && (narrative == null || narrative.isBlank())) {
			throw new IllegalArgumentException("LLM·TEMPLATE은 서술이 있어야 합니다. narrative가 비어 있습니다.");
		}
	}

	public static NarrativeResultDto llm(String narrative) {
		return new NarrativeResultDto(narrative, NarrativeSource.LLM);
	}

	public static NarrativeResultDto template(String narrative) {
		return new NarrativeResultDto(narrative, NarrativeSource.TEMPLATE);
	}

	public static NarrativeResultDto none() {
		return new NarrativeResultDto(null, NarrativeSource.NONE);
	}

	public boolean hasNarrative() {
		return this.narrative != null;
	}
}
