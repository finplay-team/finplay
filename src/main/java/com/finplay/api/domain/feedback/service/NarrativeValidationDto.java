package com.finplay.api.domain.feedback.service;

import java.util.List;

public record NarrativeValidationDto(List<String> detectedExpressions) {

	public NarrativeValidationDto {
		detectedExpressions = List.copyOf(detectedExpressions);
	}

	public boolean passed() {
		return this.detectedExpressions.isEmpty();
	}
}
